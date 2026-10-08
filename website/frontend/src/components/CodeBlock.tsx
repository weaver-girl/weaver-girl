interface CodeBlockProps {
  code: string;
  /** Text shown in the window title bar, e.g. a file name. */
  file?: string;
}

const COMMENT = /(\/\/.*|#.*)$/;

/** One highlighted line. Comments win over everything else on the line. */
function highlight(line: string): React.ReactNode[] {
  const comment = COMMENT.exec(line);
  const head = comment ? line.slice(0, comment.index) : line;
  const nodes: React.ReactNode[] = [];
  const token = /("(?:[^"\\]|\\.)*"|`(?:[^`\\]|\\.)*`)|(@\w+)|(^\s*-?\s*[\w.]+\s*(?=:))|(--?[\w.]+)/g;
  let cursor = 0;
  let match: RegExpExecArray | null;
  while ((match = token.exec(head)) !== null) {
    if (match.index > cursor) nodes.push(head.slice(cursor, match.index));
    const [raw, str, ann, key, flag] = match;
    const cls = str ? "tok-str" : ann ? "tok-ann" : key ? "tok-key" : flag ? "tok-flag" : "";
    nodes.push(
      <span key={match.index} className={cls}>
        {raw}
      </span>,
    );
    cursor = match.index + raw.length;
  }
  if (cursor < head.length) nodes.push(head.slice(cursor));
  if (comment) {
    nodes.push(
      <span key="c" className="tok-cmt">
        {comment[1]}
      </span>,
    );
  }
  return nodes;
}

/** Terminal-style window with lightweight syntax colouring. */
export function CodeBlock({ code, file }: CodeBlockProps) {
  const lines = code.replace(/\n$/, "").split("\n");
  return (
    <div className="code-window">
      <div className="code-bar">
        <span className="code-dots">
          <i />
          <i />
          <i />
        </span>
        {file ?? "shell"}
      </div>
      <pre className="code-block">
        <code>
          {lines.map((line, i) => (
            <span key={i}>
              {/* A leading flag is a wrapped continuation, not a new command. */}
              {/^(mvn|java|curl) /.test(line) && !line.startsWith("-") ? (
                <>
                  <span className="tok-cmd">{line.split(" ")[0]}</span>
                  {highlight(line.slice(line.indexOf(" ")))}
                </>
              ) : (
                highlight(line)
              )}
              {i < lines.length - 1 ? "\n" : ""}
            </span>
          ))}
        </code>
      </pre>
    </div>
  );
}
