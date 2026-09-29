import type { Metadata } from "next";
import "./styles.css";
import { QueryProvider } from "./query-provider";
import { PageTransitionProvider } from "@/features/workspace/components/page-transition";

export const metadata: Metadata = {
  title: "Paper T-Rail — Analysis Runs",
  description: "Review traceable Analysis Runs for academic documents.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" data-scroll-behavior="smooth">
      <body>
        <PageTransitionProvider>
          <QueryProvider>{children}</QueryProvider>
        </PageTransitionProvider>
      </body>
    </html>
  );
}
