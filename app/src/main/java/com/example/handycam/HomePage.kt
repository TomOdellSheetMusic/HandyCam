package com.example.handycam

internal val homePageHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <title>HandyCam</title>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        <style>
                            *, *::before, *::after { box-sizing: border-box; }
                            body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; margin: 0; padding: 24px; background: #f0f0f0; color: #1a1a1a; }
                            .container { max-width: 520px; margin: 0 auto; }
                            h1 { font-size: 1.4rem; font-weight: 600; margin: 0 0 4px; }
                            p { margin: 0 0 20px; font-size: 0.85rem; color: #666; }
                            .card { background: white; border: 1px solid #e0e0e0; border-radius: 8px; padding: 16px; margin-bottom: 12px; }
                            .card-title { font-size: 0.75rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.06em; color: #666; margin: 0 0 10px; }
                            .endpoint { display: grid; grid-template-columns: auto 1fr; gap: 4px 12px; font-size: 0.875rem; margin: 6px 0; }
                            .method { font-weight: 600; color: #1a73e8; }
                            .path { color: #1a1a1a; }
                            .desc { grid-column: 2; color: #666; font-size: 0.8rem; }
                            a { color: #1a73e8; }
                        </style>
                    </head>
                    <body>
                        <div class="container">
                            <h1>HandyCam</h1>
                            <p>HTTP Control Server</p>
                            <div class="card">
                                <div class="card-title">Web UI</div>
                                <a href="/camera">Open Camera Control</a>
                            </div>
                            <div class="card">
                                <div class="card-title">API Endpoints</div>
                                <div class="endpoint">
                                    <span class="method">GET</span><span class="path">/status</span>
                                    <span class="desc">Server status (JSON)</span>
                                </div>
                                <div class="endpoint">
                                    <span class="method">GET</span><span class="path">/info</span>
                                    <span class="desc">Server information (JSON)</span>
                                </div>
                                <div class="endpoint">
                                    <span class="method">GET</span><span class="path">/health</span>
                                    <span class="desc">Health check</span>
                                </div>
                                <div class="endpoint">
                                    <span class="method">POST</span><span class="path">/api/camera/start</span>
                                    <span class="desc">Start camera streaming</span>
                                </div>
                                <div class="endpoint">
                                    <span class="method">POST</span><span class="path">/api/camera/stop</span>
                                    <span class="desc">Stop camera streaming</span>
                                </div>
                                <div class="endpoint">
                                    <span class="method">POST</span><span class="path">/api/camera/switch</span>
                                    <span class="desc">Switch camera — body: {"camera": "back|front"}</span>
                                </div>
                                <div class="endpoint">
                                    <span class="method">POST</span><span class="path">/api/settings/zoom</span>
                                    <span class="desc">Set zoom level — body: {"zoom": 0.0-1.0}</span>
                                </div>
                            </div>
                        </div>
                    </body>
                    </html>
                    """.trimIndent()
