Raw bytes captured from real programs running in a 80x24 PTY with `TERM=xterm-256color`
(`python3 pty` harness; the program was left running when the capture stopped, so the stream
ends on its full-screen UI):

- `less.bin`: `less sample.txt`, then one space (page down). sample.txt = 79 lines "line N: the quick brown fox".
- `nano.bin`: `nano sample.txt`.
- `vi.bin`: `vi -u NONE sample.txt`, then `G`.
- `htop.bin`: `htop` after two seconds. Contents depend on the machine it ran on.

`TerminalFixtureTest` replays them through `TerminalEmulator`.
