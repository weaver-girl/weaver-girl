interface PageHeroProps {
  kicker: string;
  title: string;
  lede: string;
}

/** Heading block shared by every page except the home page. */
export function PageHero({ kicker, title, lede }: PageHeroProps) {
  return (
    <header className="page-hero">
      <div className="eyebrow">
        <span className="eyebrow-dot" />
        {kicker}
      </div>
      <h1>{title}</h1>
      <p>{lede}</p>
    </header>
  );
}
