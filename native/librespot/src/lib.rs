use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    path::PathBuf,
    sync::{Mutex, OnceLock},
    time::Duration,
};

use jni::{
    JNIEnv,
    objects::{JIntArray, JObject, JString},
    sys::{jboolean, jlong, jstring},
};
use librespot_core::{
    Session, SessionConfig, authentication::Credentials, audio_key::AudioKeyError,
    cdn_url::CdnUrl, config::KEYMASTER_CLIENT_ID, error::ErrorKind, http_client::HttpClientError,
    spclient::RequestStrategy, SpotifyUri,
};
use librespot_metadata::audio::{file::AudioFileFormat, item::AudioItem};
use serde_json::{Value, json};
use tokio::runtime::{Builder, Runtime};
use url::Url;

static RUNTIME: OnceLock<Result<Runtime, ()>> = OnceLock::new();
static STATE: Mutex<State> = Mutex::new(State { generation: 0, session: None });

struct State {
    generation: u64,
    session: Option<Session>,
}

struct Failure {
    code: &'static str,
    retry_after: Option<String>,
}

impl Failure {
    fn new(code: &'static str) -> Self {
        Self { code, retry_after: None }
    }

    fn json(self) -> Value {
        json!({"error": self.code, "retryAfter": self.retry_after})
    }
}

fn native_error(error: librespot_core::Error) -> Failure {
    if let Some(HttpClientError::RateLimited(retry_after)) =
        error.error.downcast_ref::<HttpClientError>()
    {
        return Failure { code: "RATE_LIMITED", retry_after: retry_after.clone() };
    }
    Failure::new(match error.kind {
        ErrorKind::Unauthenticated => "AUTH",
        ErrorKind::PermissionDenied => "ACCESS_DENIED",
        ErrorKind::ResourceExhausted => "RATE_LIMITED",
        ErrorKind::DeadlineExceeded => "TIMEOUT",
        ErrorKind::Unavailable => "NETWORK",
        ErrorKind::NotFound => "TRACK_UNAVAILABLE",
        _ => "INVALID_RESPONSE",
    })
}

fn runtime() -> Result<&'static Runtime, Failure> {
    RUNTIME.get_or_init(|| {
        // Upstream trace diagnostics include bearer and reusable credentials. No Rust log sink.
        log::set_max_level(log::LevelFilter::Off);
        std::panic::set_hook(Box::new(|_| {}));
        Builder::new_multi_thread().worker_threads(2).enable_all().build().map_err(|_| ())
    }).as_ref().map_err(|_| Failure::new("NATIVE_UNAVAILABLE"))
}

fn state() -> Result<std::sync::MutexGuard<'static, State>, Failure> {
    STATE.lock().map_err(|_| Failure::new("INVALID_RESPONSE"))
}

fn current(generation: u64) -> bool {
    state().map(|s| s.generation == generation).unwrap_or(false)
}

fn close() {
    if let Ok(mut state) = state() {
        state.generation = state.generation.wrapping_add(1);
        if let Some(session) = state.session.take() {
            session.shutdown();
        }
    }
}

fn budget(timeout_ms: jlong) -> Duration {
    Duration::from_millis(timeout_ms.clamp(1, 20_000) as u64)
}

fn read_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, Failure> {
    env.get_string(value).map(Into::into).map_err(|_| Failure::new("INVALID_RESPONSE"))
}

fn reply<F>(mut env: JNIEnv<'_>, work: F) -> jstring
where
    F: FnOnce(&mut JNIEnv<'_>) -> Result<Value, Failure>,
{
    let result = catch_unwind(AssertUnwindSafe(|| work(&mut env)));
    let value = match result {
        Ok(Ok(value)) => value,
        Ok(Err(failure)) => failure.json(),
        Err(_) => Failure::new("INVALID_RESPONSE").json(),
    };
    env.new_string(value.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_rukamori_archivetune_spotify_librespot_SpotifyLibrespotNative_nativeConnect(
    env: JNIEnv<'_>, _object: JObject<'_>, token: JString<'_>, user_id: JString<'_>,
    device_id: JString<'_>, cache_dir: JString<'_>, proxy: JString<'_>, timeout_ms: jlong,
) -> jstring {
    reply(env, |env| {
        let token = read_string(env, &token)?;
        let user_id = read_string(env, &user_id)?;
        let device_id = read_string(env, &device_id)?;
        let cache_dir = read_string(env, &cache_dir)?;
        let proxy = read_string(env, &proxy)?;
        if token.is_empty() || user_id.is_empty() || device_id.is_empty() {
            return Err(Failure::new("AUTH"));
        }
        let proxy = if proxy.is_empty() { None } else {
            let url = Url::parse(&proxy).map_err(|_| Failure::new("INVALID_RESPONSE"))?;
            if !matches!(url.scheme(), "http" | "https") {
                return Err(Failure::new("INVALID_RESPONSE"));
            }
            Some(url)
        };
        let rt = runtime()?;
        let generation = {
            let mut state = state()?;
            state.generation = state.generation.wrapping_add(1);
            if let Some(old) = state.session.take() { old.shutdown(); }
            state.generation
        };
        let session = {
            let _enter = rt.enter();
            Session::new(SessionConfig {
                // AP OAuth credentials belong to the upstream Keymaster profile, including on
                // Android. login5 documents this override for StoredCredential (not password login).
                client_id: KEYMASTER_CLIENT_ID.to_owned(),
                device_id,
                tmp_dir: PathBuf::from(cache_dir),
                proxy,
                ..SessionConfig::default()
            }, None)
        };
        session.spclient().set_strategy(RequestStrategy::TryTimes(1));
        {
            let mut state = state()?;
            if state.generation != generation {
                session.shutdown();
                return Err(Failure::new("ACCOUNT_CHANGED"));
            }
            // Publish before connecting: logout can cancel an in-flight AP connection too.
            state.session = Some(session.clone());
        }
        let result = rt.block_on(async { tokio::time::timeout(budget(timeout_ms), async {
            session.connect(Credentials::with_access_token(token), false).await.map_err(native_error)?;
            if session.username() != user_id { return Err(Failure::new("ACCOUNT_CHANGED")); }
            // ProductInfo arrives asynchronously after APWelcome. Wait inside the caller's budget.
            loop {
                if !current(generation) || session.is_invalid() { return Err(Failure::new("ACCOUNT_CHANGED")); }
                if let Some(product) = session.get_user_attribute("type") {
                    return if product == "premium" { Ok(()) } else { Err(Failure::new("NOT_PREMIUM")) };
                }
                tokio::time::sleep(Duration::from_millis(25)).await;
            }
        }).await }).map_err(|_| Failure::new("TIMEOUT")).and_then(|r| r);
        if let Err(failure) = result {
            session.shutdown();
            let mut state = state()?;
            if state.generation == generation { state.session = None; }
            return Err(failure);
        }
        if !current(generation) {
            session.shutdown();
            return Err(Failure::new("ACCOUNT_CHANGED"));
        }
        Ok(json!({"ok": true}))
    })
}

async fn resolve(session: &Session, uri: SpotifyUri, formats: Vec<i32>) -> Result<Value, Failure> {
    let mut item = AudioItem::get_file(session, uri).await.map_err(native_error)?;
    // Country/explicit/release restrictions are authoritative. Relinking only applies when the
    // available recording lacks files, as in the upstream player; bound alternatives to three.
    if item.availability.is_err() { return Err(Failure::new("TRACK_UNAVAILABLE")); }
    if item.files.is_empty() {
        let alternatives = item.alternatives.take().map(|a| a.0).unwrap_or_default();
        let mut found = None;
        for alternative in alternatives.into_iter().take(3) {
            let candidate = AudioItem::get_file(session, alternative).await.map_err(native_error)?;
            if candidate.availability.is_ok() && !candidate.files.is_empty() {
                found = Some(candidate);
                break;
            }
        }
        item = found.ok_or_else(|| Failure::new("TRACK_UNAVAILABLE"))?;
    }
    let chosen = formats.into_iter().find_map(|value| {
        let format = match value {
            0 => AudioFileFormat::OGG_VORBIS_96,
            1 => AudioFileFormat::OGG_VORBIS_160,
            2 => AudioFileFormat::OGG_VORBIS_320,
            _ => return None,
        };
        item.files.get(&format).map(|file| (value, *file))
    }).ok_or_else(|| Failure::new("UNSUPPORTED_FORMAT"))?;
    let SpotifyUri::Track { id } = &item.track_id else { return Err(Failure::new("TRACK_UNAVAILABLE")); };
    let key = session.audio_key().request(*id, chosen.1).await.map_err(|error| {
        if matches!(error.error.downcast_ref::<AudioKeyError>(), Some(AudioKeyError::AesKey)) {
            Failure::new("AUDIO_KEY")
        } else { native_error(error) }
    })?;
    let storage = CdnUrl::new(chosen.1).resolve_audio(session).await.map_err(native_error)?;
    let urls: Vec<String> = storage.try_get_urls().map_err(native_error)?.into_iter().filter(|url| {
        Url::parse(url).map(|u| u.scheme() == "https" && u.host_str().is_some() &&
            u.username().is_empty() && u.password().is_none()).unwrap_or(false)
    }).take(4).map(str::to_owned).collect();
    if urls.is_empty() { return Err(Failure::new("TRACK_UNAVAILABLE")); }
    if session.get_user_attribute("type").as_deref() != Some("premium") {
        return Err(Failure::new("NOT_PREMIUM"));
    }
    let album = match &item.unique_fields {
        librespot_metadata::audio::item::UniqueFields::Track { album, .. } => Some(album),
        _ => None,
    };
    Ok(json!({
        "trackId": item.track_id.to_id(), "title": item.name,
        "artist": item.unique_fields.artists_display(), "album": album,
        "durationMs": item.duration_ms, "format": chosen.0, "fileId": chosen.1.to_base16(),
        "keyHex": key.0.iter().map(|b| format!("{b:02x}")).collect::<String>(), "urls": urls,
    }))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_rukamori_archivetune_spotify_librespot_SpotifyLibrespotNative_nativeResolve(
    env: JNIEnv<'_>, _object: JObject<'_>, track_uri: JString<'_>, formats: JIntArray<'_>, timeout_ms: jlong,
) -> jstring {
    reply(env, |env| {
        let uri = SpotifyUri::from_uri(&read_string(env, &track_uri)?).map_err(|_| Failure::new("TRACK_UNAVAILABLE"))?;
        if !matches!(uri, SpotifyUri::Track { .. }) { return Err(Failure::new("TRACK_UNAVAILABLE")); }
        let count = env.get_array_length(&formats).map_err(|_| Failure::new("INVALID_RESPONSE"))?;
        if !(1..=3).contains(&count) { return Err(Failure::new("UNSUPPORTED_FORMAT")); }
        let mut selected = vec![0; count as usize];
        env.get_int_array_region(&formats, 0, &mut selected).map_err(|_| Failure::new("INVALID_RESPONSE"))?;
        let (session, generation) = {
            let state = state()?;
            (state.session.clone().ok_or_else(|| Failure::new("AUTH"))?, state.generation)
        };
        if session.is_invalid() { return Err(Failure::new("NETWORK")); }
        let result = runtime()?.block_on(async {
            tokio::time::timeout(budget(timeout_ms), resolve(&session, uri, selected)).await
        })
            .map_err(|_| Failure::new("TIMEOUT"))??;
        if !current(generation) || session.is_invalid() { return Err(Failure::new("ACCOUNT_CHANGED")); }
        Ok(result)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_rukamori_archivetune_spotify_librespot_SpotifyLibrespotNative_nativeSessionValid(
    _env: JNIEnv<'_>, _object: JObject<'_>,
) -> jboolean {
    catch_unwind(|| state().map(|s| s.session.as_ref().is_some_and(|s|
        !s.is_invalid() && s.get_user_attribute("type").as_deref() == Some("premium"))).unwrap_or(false))
        .unwrap_or(false) as jboolean
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_moe_rukamori_archivetune_spotify_librespot_SpotifyLibrespotNative_nativeClose(
    _env: JNIEnv<'_>, _object: JObject<'_>,
) {
    let _ = catch_unwind(close);
}
