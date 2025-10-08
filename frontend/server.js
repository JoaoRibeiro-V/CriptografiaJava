// server.js
const express = require('express')
const { createProxyMiddleware } = require('http-proxy-middleware')
const path = require('path')

const app = express()
const PORT = process.env.PORT || 3000
const BACKEND = process.env.BACKEND || 'http://localhost:8080'

// Proxy websocket path /ws to the backend (ws:true)
// and proxy any /api calls to backend too
app.use('/ws', createProxyMiddleware({
    target: BACKEND,
    changeOrigin: true,
    ws: true,
    logLevel: 'error'
}))

app.use('/api', createProxyMiddleware({
    target: BACKEND,
    changeOrigin: true,
    logLevel: 'error'
}))

// Serve static frontend
app.use(express.static(path.join(__dirname, 'public')))

// Fallback to index.html for SPA
app.get('*', (req, res) => {
    res.sendFile(path.join(__dirname, 'public', 'index.html'))
})

app.listen(PORT, () => {
    console.log(`Frontend server running on port ${PORT} -> proxying /ws to ${BACKEND}`)
})
