# Docker cleanup and final state

Cleanup completed after Jenkins artifacts and container logs were copied.

- Stopped only stp-plugin-controller, stp-plugin-agent, stp-plugin-agent-1, stp-plugin-agent-2, and stp-plugin-agent-3.
- Running containers matching stp-plugin after cleanup: none.
- Containers remain stopped, not deleted.
- Jenkins controller state volume ei9_controller_home is preserved.
- Shared FILE map-storage volume ei11_map_storage is preserved.
- No unrelated Docker resource was stopped or deleted.

Final repository verification:

- STP: production HEAD 11e1cdeaae7951137c56f1ffc3008c066e0adc64 followed by EI-11 evidence commit cbfe238; only historical EI-9/ remains untracked before this cleanup evidence commit.
- Jenkins plugin: b446df5a355b92a1a893de66aae7e598ee0c8bcd, clean.
- SonarJava: master at R0 9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c, clean; local branch ei-11-r1 preserves R1 eed6922c776e5d029266459d04ed5a69e9d6fc8f.
- No push was performed.
