interface CodeBlockProps {
  code: string;
}

/** Dark monospace block for shell and Java snippets. */
export function CodeBlock({ code }: CodeBlockProps) {
  return (
    <pre className="code-block">
      <code>{code}</code>
    </pre>
  );
}
