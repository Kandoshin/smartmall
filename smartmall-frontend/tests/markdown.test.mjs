import assert from 'node:assert/strict'
import test from 'node:test'
import { renderMarkdown } from '../src/markdown.ts'

test('AI Markdown renders lists, emphasis and code', () => {
  const html = renderMarkdown('- **查商品**：使用 `search_products`')

  assert.match(html, /<ul>/)
  assert.match(html, /<strong>查商品<\/strong>/)
  assert.match(html, /<code>search_products<\/code>/)
})

test('raw HTML and unsafe links from model output are not executable', () => {
  const html = renderMarkdown('<script>window.hacked = true</script>\n\n[危险链接](javascript:alert(1))')

  assert.doesNotMatch(html, /<script>/i)
  assert.match(html, /&lt;script&gt;/)
  assert.doesNotMatch(html, /href=["']javascript:/i)
})
