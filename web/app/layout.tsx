import type { Metadata } from "next";
import "./styles.css";

export const metadata: Metadata = {
  title: "Paper T-Rail — Upload a Source Document",
  description: "Start a traceable analysis run for an English academic PDF.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
