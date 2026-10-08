interface PageHeroProps {
  kicker: string;
  title: string;
  lede: string;
}

/** Heading block shared by every page except the home page. */
export function PageHero({ kicker, title, lede }: PageHeroProps) {
  return (
    <header className="page-hero">
      <p className="kicker">{kicker}</p>
      <h1>{title}</h1>
      <p className="dek">{lede}</p>
    </header>
  );
}
