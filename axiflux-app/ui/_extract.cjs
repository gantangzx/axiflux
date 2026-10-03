const fs = require('fs')
const css = fs.readFileSync('D:\\software\\QClaw\\v0.2.37.630\\resources\\Axiflux\\node_modules\\Axiflux\\dist\\control-ui\\assets\\index-DpUKIESN.css', 'utf8')
const idx = css.indexOf('chat-tool-card')
if (idx >= 0) {
  const start = Math.max(0, idx - 3000)
  const end = Math.min(css.length, idx + 6000)
  console.log(css.slice(start, end))
} else {
  const idx2 = css.indexOf('tool-card')
  if (idx2 >= 0) {
    const start = Math.max(0, idx2 - 3000)
    const end = Math.min(css.length, idx2 + 6000)  
    console.log(css.slice(start, end))
  } else {
    console.log('NOT FOUND')
  }
}