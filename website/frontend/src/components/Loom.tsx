/**
 * Decorative loom: warp threads with a shuttle weaving a weft through them.
 * Pure CSS animation driven by the classes in index.css is not enough for the
 * moving shuttle path, so the motion lives in SVG <animate> elements.
 */
export function Loom() {
  const warps = Array.from({ length: 9 }, (_, i) => 46 + i * 34);
  return (
    <svg className="loom" viewBox="0 0 400 360" fill="none" aria-hidden="true">
      <defs>
        <linearGradient id="warp" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#e4b15a" stopOpacity="0" />
          <stop offset="0.18" stopColor="#e4b15a" stopOpacity="0.85" />
          <stop offset="0.82" stopColor="#e08a78" stopOpacity="0.85" />
          <stop offset="1" stopColor="#e08a78" stopOpacity="0" />
        </linearGradient>
        <linearGradient id="shuttle" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#f6e2b8" />
          <stop offset="1" stopColor="#c4843c" />
        </linearGradient>
      </defs>

      {warps.map((x) => (
        <path
          key={x}
          d={`M${x} 6 C ${x - 10} 120, ${x + 10} 230, ${x} 354`}
          stroke="url(#warp)"
          strokeWidth="1.4"
        />
      ))}

      {/* the woven band */}
      {Array.from({ length: 7 }, (_, row) => {
        const y = 96 + row * 22;
        return warps.slice(0, -1).map((x, col) => {
          const over = (row + col) % 2 === 0;
          return (
            <path
              key={`${row}-${col}`}
              d={over ? `M${x} ${y - 7} Q ${x + 17} ${y}, ${x + 34} ${y - 7}` : `M${x} ${y + 7} Q ${x + 17} ${y}, ${x + 34} ${y + 7}`}
              stroke={over ? "#f3d7a1" : "#e08a78"}
              strokeOpacity={0.55 + (row % 3) * 0.12}
              strokeWidth="1.6"
              strokeLinecap="round"
            />
          );
        });
      })}

      {/* shuttle */}
      <g>
        <ellipse cx="0" cy="0" rx="27" ry="9" fill="url(#shuttle)" />
        <ellipse cx="0" cy="0" rx="9" ry="4.5" fill="#2a2118" fillOpacity="0.85" />
        <animateTransform
          attributeName="transform"
          type="translate"
          dur="6.5s"
          repeatCount="indefinite"
          values="60,150; 340,194; 60,238; 340,150; 60,150"
          keyTimes="0; 0.25; 0.5; 0.75; 1"
          calcMode="spline"
          keySplines="0.4 0 0.2 1; 0.4 0 0.2 1; 0.4 0 0.2 1; 0.4 0 0.2 1"
        />
      </g>
    </svg>
  );
}
