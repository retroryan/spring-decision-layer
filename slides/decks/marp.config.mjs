import { Marp } from '@marp-team/marp-core'
import cypher from 'highlightjs-cypher'

export default {
  themeSet: ['./neo4j.css'],
  engine: (opts) => {
    const marp = new Marp(opts)
    marp.highlighter = (code, lang) => {
      const hljs = marp.highlightjs
      if (!hljs.getLanguage('cypher')) {
        hljs.registerLanguage('cypher', cypher)
      }
      const language = hljs.getLanguage(lang) ? lang : 'plaintext'
      return hljs.highlight(code, { language }).value
    }
    return marp
  },
}
