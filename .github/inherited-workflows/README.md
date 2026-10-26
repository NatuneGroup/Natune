# Inherited workflows (temporarily inactive)

All inherited workflow/helper source files remain unchanged after their bootstrap
move out of `.github/workflows/`. No inherited workflow is active, run or published.

After legitimate import-gate resolution and reviewed private setup, restore/adapt
the original YAML set and useful behavior for `main` (Stable/default) and `dev`
(integration), with private-safe links/publication and existing signing-secret
names. Remote `main` and `dev` already exist, both at the independent foundation commit
`c047a2002a7c35eb9ed30b6a8eec72951f7f2f48`, not at the approved dev-derived snapshot.
Replacing or updating them needs separate authorization. This temporary quarantine is not
the final workflow scope.

`foundation-readiness.yml` is a byte-identical copy of the foundation's manual readiness
workflow from that commit. It is not one of the 13 inherited upstream files, and it is not
active. On dispatch it runs the foundation's `:shared` tests and Linux, Windows and macOS
native app-image jobs. Its `:shared` targets are not in the port's settings, so it cannot
pass as written. M10 may adapt its native-host matrix. Its historical runs test the
foundation only.

The unused Telethon uploader remains an inactive reference and must not be newly
activated. No file here is enabled, moved into `.github/workflows/`, or dispatched by this
merge; this directory is not an instruction to run inherited automation now.
