import Link from "next/link";
import { UploadDashboard } from "@/components/upload-dashboard";

export default function Home() {
  return (
    <main className="page-shell">
      <header className="masthead">
        <Link className="wordmark" href="/" aria-label="Paper T-Rail home">
          <span className="wordmark-mark" aria-hidden="true">P</span>
          <span>Paper T-Rail</span>
        </Link>
        <span className="local-badge"><span className="status-dot" /> Local workspace</span>
      </header>
      <section className="hero">
        <p className="eyebrow">ACADEMIC EVIDENCE ENGINE</p>
        <h1>Put your paper on a<br /><em>traceable track.</em></h1>
        <p className="hero-copy">Start an analysis run from an English, text-based academic PDF. Your source file stays in this local workspace.</p>
      </section>
      <UploadDashboard />
      <footer className="footer-note">
        <span>Private by default · No external providers are enabled</span>
        <span>Paper T-Rail reports are research triage, not certification or grading.</span>
      </footer>
    </main>
  );
}
