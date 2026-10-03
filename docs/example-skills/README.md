# Example skills (archive)

The `axiflux-skills` Maven module was removed in the 2026-09-04 P2 cleanup: it
contained no Java code and was never on any module's classpath — the runtime
skill loader scans the filesystem directory given by `axiflux.skills.root-dir`
(default `./skills`, i.e. `<workdir>/skills/`), not this module.

This directory keeps the one example skill (`reminder/`) that used to ship in
that module. To activate it on a deployment, copy it into the configured skills
root, e.g.:

```
cp -r docs/example-skills/reminder ./skills/reminder
```

(`./skills/` relative to the app working directory; see
`axiflux.skills.root-dir` in application yml.)
