import { useMemo, useRef, useState } from 'react';
import type { NavPoint } from '../api';
import { fmtMoney } from '../format';

/** NAV over time: one series, crosshair + tooltip on hover, keyboard-steppable. */
export default function NavChart({ points, currency }: { points: NavPoint[]; currency: string }) {
  const W = 720, H = 220, L = 72, R = 12, T = 12, B = 28;
  const [hover, setHover] = useState<number | null>(null);
  const svg = useRef<SVGSVGElement>(null);
  const series = useMemo(() => points.map((p) => ({ date: p.date, nav: Number(p.nav) })), [points]);
  if (series.length < 2) return <p className="muted small">The NAV history appears after two or more days of activity.</p>;

  const values = series.map((p) => p.nav);
  let lo = Math.min(...values), hi = Math.max(...values);
  if (lo === hi) { lo -= 1; hi += 1; }
  const pad = (hi - lo) * 0.08; lo -= pad; hi += pad;
  const x = (i: number) => L + (i / (series.length - 1)) * (W - L - R);
  const y = (v: number) => T + (1 - (v - lo) / (hi - lo)) * (H - T - B);
  const path = series.map((p, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(p.nav).toFixed(1)}`).join('');
  const ticks = Array.from({ length: 4 }, (_, i) => lo + ((hi - lo) * (i + 0.5)) / 4);
  const dateTicks = [0, Math.floor((series.length - 1) / 2), series.length - 1];
  const compact = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 2 });

  const move = (clientX: number) => {
    const r = svg.current!.getBoundingClientRect();
    const px = ((clientX - r.left) / r.width) * W;
    setHover(Math.max(0, Math.min(series.length - 1, Math.round(((px - L) / (W - L - R)) * (series.length - 1)))));
  };
  const h = hover !== null ? series[hover] : null;

  return (
    <div className="chart">
      <svg ref={svg} viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Net asset value from ${series[0].date} to ${series.at(-1)!.date}`}
        tabIndex={0} onMouseMove={(e) => move(e.clientX)} onMouseLeave={() => setHover(null)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowRight') setHover((i) => Math.min(series.length - 1, (i ?? -1) + 1));
          if (e.key === 'ArrowLeft') setHover((i) => Math.max(0, (i ?? series.length) - 1));
        }}>
        {ticks.map((t) => (
          <g key={t}><line x1={L} x2={W - R} y1={y(t)} y2={y(t)} className="grid" />
            <text x={L - 8} y={y(t)} className="axis" textAnchor="end" dominantBaseline="middle">{compact.format(t)}</text></g>
        ))}
        {dateTicks.map((i) => <text key={i} x={x(i)} y={H - 8} className="axis" textAnchor={i === 0 ? 'start' : i === series.length - 1 ? 'end' : 'middle'}>{series[i].date}</text>)}
        <path d={path} className="line" />
        {h && hover !== null && (<g>
          <line x1={x(hover)} x2={x(hover)} y1={T} y2={H - B} className="crosshair" />
          <circle cx={x(hover)} cy={y(h.nav)} r={4.5} className="dot" />
        </g>)}
        <rect x={L} y={T} width={W - L - R} height={H - T - B} fill="transparent" />
      </svg>
      {h && hover !== null && (
        <div className="tooltip" style={{ left: `${(x(hover) / W) * 100}%` }}>
          <div className="small muted">{h.date}</div><strong>{currency} {fmtMoney(h.nav)}</strong>
        </div>)}
    </div>
  );
}
