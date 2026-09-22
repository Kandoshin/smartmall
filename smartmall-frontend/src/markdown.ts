import MarkdownIt from 'markdown-it'

const markdown = new MarkdownIt({
  // Model output is untrusted. Markdown syntax is supported, raw HTML is not.
  html: false,
  breaks: true,
  linkify: true,
  typographer: false,
})

export function renderMarkdown(source: string) {
  return markdown.render(source)
}
