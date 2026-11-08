// Natune contributors, GPL-3.0-or-later. One provider, explicit connection mode.
function connection(context) {
  const mode = Natune.text(context, 'mode') || 'account';
  if (mode === 'instance') return InstanceProvider;
  if (mode === 'account') return AccountProvider;
  throw Natune.error('VERIFY_REQUIRED', 'Choose an account or public instance connection');
}
async function searchTracks(query, limit, context) { return connection(context).searchTracks(query, limit, context); }
async function getTrackStreamUrl(trackId, quality, context) { return connection(context).getTrackStreamUrl(trackId, quality, context); }
module.exports = {searchTracks, getTrackStreamUrl};
