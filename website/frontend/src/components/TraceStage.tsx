import { useEffect, useState } from "react";

interface Span {
  service: string;
  name: string;
  /** Start offset inside the 100-unit trace window. */
  start: number;
  /** Width inside the 100-unit trace window. */
  width: number;
  ms: string;
  tone: "ok" | "slow" | "hot";
  depth: number;
}

const SPANS: Span[] = [
  { service: "gateway", name: "GET /api/orders", start: 0, width: 100, ms: "186ms", tone: "ok", depth: 0 },
  { service: "order", name: "OrderService.create", start: 6, width: 88, ms: "164ms", tone: "ok", depth: 1 },
  { service: "mysql", name: "INSERT orders", start: 14, width: 22, ms: "41ms", tone: "ok", depth: 2 },
  { service: "redis", name: "GET stock:8841", start: 40, width: 7, ms: "3ms", tone: "ok", depth: 2 },
  { service: "kafka", name: "publish order.created", start: 52, width: 34, ms: "96ms", tone: "slow", depth: 2 },
  { service: "pay", name: "PaymentClient.charge", start: 62, width: 30, ms: "121ms", tone: "hot", depth: 3 },
];

const PLUGINS = ["servlet", "jdbc", "redis", "kafka", "httpclient"];

/**
 * The hero's product visual: a trace waterfall that plays in on mount, sitting
 * on a lit stage. Static markup plus CSS transitions, no canvas.
 */
export function TraceStage() {
  const [played, setPlayed] = useState(false);

  useEffect(() => {
    const id = window.setTimeout(() => setPlayed(true), 250);
    return () => window.clearTimeout(id);
  }, []);

  return (
    <div className="stage">
      <div className="stage-glow" />
      <div className={`console${played ? " played" : ""}`}>
        <div className="console-bar">
          <span className="code-dots">
            <i />
            <i />
            <i />
          </span>
          <b>weaver-girl</b>
          <span className="console-trace">trace 7f3a…c21</span>
          <span className="live">
            <i />
            LIVE
          </span>
        </div>

        <div className="console-meta">
          <span>
            <em>186ms</em> 总耗时
          </span>
          <span>
            <em>6</em> 个 span
          </span>
          <span>
            <em>5</em> 个插件命中
          </span>
          <span className="ok">
            <em>0</em> 错误
          </span>
        </div>

        <ol className="waterfall">
          {SPANS.map((span) => (
            <li key={span.name} style={{ paddingLeft: 16 + span.depth * 18 }}>
              <span className="wf-service">{span.service}</span>
              <span className="wf-name">{span.name}</span>
              <span className="wf-track">
                <i
                  className={`wf-bar ${span.tone}`}
                  style={{ left: `${span.start}%`, width: played ? `${span.width}%` : "0%" }}
                />
              </span>
              <span className="wf-ms">{span.ms}</span>
            </li>
          ))}
        </ol>

        <div className="console-plugins">
          {PLUGINS.map((name) => (
            <span key={name}>{name}</span>
          ))}
        </div>
      </div>
    </div>
  );
}
