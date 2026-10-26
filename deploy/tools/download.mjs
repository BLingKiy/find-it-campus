// 构建期下载小工具：某些 Windows 环境的 PowerShell/Schannel 无法完成 HTTPS，
// 但 Node 22 的 fetch 可用。build-package.ps1 把它作为 Invoke-WebRequest / curl 之后的兜底。
import { writeFile } from 'node:fs/promises'

const [url, out] = process.argv.slice(2)
if (!url || !out) {
  console.error('usage: node download.mjs <url> <output-file>')
  process.exit(2)
}

try {
  const response = await fetch(url, { redirect: 'follow' })
  if (!response.ok) throw new Error(`HTTP ${response.status} ${response.statusText}`)
  const buffer = Buffer.from(await response.arrayBuffer())
  await writeFile(out, buffer)
  console.log(`downloaded ${buffer.length} bytes -> ${out}`)
} catch (error) {
  console.error(`download failed: ${error.message}`)
  process.exit(1)
}
