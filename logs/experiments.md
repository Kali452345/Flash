# Experiments Log

## EXP-001 — First successful 10MB WS mesh transfer (Samsung SM-G986U1 → Infinix X6882B)

### Date
2026-08-24 (build with ERROR-015 fixes + ack-drain grace)

### Setup
- Link: mobile hotspot (Infinix X6882B hosting assumed from peer name; band unknown — likely 2.4 GHz)
- Transport: single WebSocket, 2 logical StreamChannels multiplexed under one write lock
- Payload: 10 MB deterministic test stream; 160 × 64 KiB chunks; ACK every 32 verified chunks

### Results
- Send phase: ~4.9 s (13:33:24.97 → 13:33:29.89) ≈ **2.0 MB/s (~16 Mbps) wire throughput**
- End-to-end incl. final ACK + COMPLETE: ~7.3 s ≈ **1.4 MB/s confirmed**
- Outcome: `Completed`, receiver whole-file `verified=true`; a real 97 KB JPG also transferred and verified in the same session
- Sender monitor-contention warnings on the single WS write lock, waits 210–965 ms (expected: both channels serialize on one socket)
- GC: notable LOS churn (~60 MB large objects) from 64 KB frame arrays

### Interpretation
- Throughput is plausibly near a 2.4 GHz hotspot ceiling (~15–25 Mbps), NOT yet evidence of an engine bottleneck.
- "Multi-stream" currently = interleaved scheduling over ONE socket (ADR-017 revisit condition now met): real parallel streams need N sockets.

### Next experiments
1. Same transfer over 5 GHz router link (same devices).
2. Chunk-size sweep 64/128/256 KB on the faster link.
3. N-socket streams (2/4 real connections) once implemented.

### Status
BASELINE recorded. No optimization conclusions valid until (1) exists.

## Notes
Placeholder for future physical-device networking experiments (see AGENTS.md §9 format).
