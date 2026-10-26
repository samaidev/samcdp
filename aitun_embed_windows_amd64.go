package main

// aitun_embed_windows_amd64.go — v1.4.7: bundle the aitun client INSIDE the
// Windows executable (user request: every release artifact must carry its own
// tunnel client — no network dependency on first run).

import _ "embed"

//go:embed aitunbin/aitun-client-windows-amd64.exe
var aitunEmbedded []byte
