# EI-12 Docker cleanup

Verified after validation on 2026-09-14 (Europe/Berlin):

```text
stp-plugin-agent-3 d8d7db098e1e Exited (143)
stp-plugin-agent   e45ad9869d65 Exited (143)
stp-plugin-agent-1 ebb482880235 Exited (143)
stp-plugin-agent-2 dd225962a3a1 Exited (143)
stp-plugin-controller dead9529291c Exited (143)
```

No matching EI-12/Jenkins container remained running. The `ei9_controller_home` Jenkins-state volume and `ei11_map_storage` inspection volume remain preserved. No supporting-service container was started. Unrelated Docker resources were not changed.

Final worktrees:

- Smart Test Picker: clean except for the required preserved untracked `EI-9/` directory.
- Jenkins plugin: clean.
- SonarJava: clean `master` at R0; `ei-11-r1` remains preserved.
- Push performed: no.
