"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { createContext, useCallback, useContext, useLayoutEffect, useRef, type ComponentProps, type ReactNode } from "react";

type PageTransitionDirection = "forward" | "back";
type PageNavigation = (href: string, direction: PageTransitionDirection) => void;
type PendingNavigation = {
  pathname: string;
  timeoutId: number;
  complete: () => void;
};

const PageNavigationContext = createContext<PageNavigation | null>(null);

export function PageTransitionProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const pendingNavigation = useRef<PendingNavigation | null>(null);
  const activeTransitionId = useRef(0);

  useLayoutEffect(() => {
    if (pendingNavigation.current?.pathname === pathname) {
      pendingNavigation.current.complete();
    }
  }, [pathname]);

  const navigate = useCallback<PageNavigation>((href, direction) => {
    const target = new URL(href, window.location.href);
    if (target.origin !== window.location.origin || target.pathname === pathname) {
      router.push(href);
      return;
    }

    if (typeof document.startViewTransition !== "function") {
      router.push(href);
      return;
    }

    pendingNavigation.current?.complete();
    const transitionId = ++activeTransitionId.current;
    document.documentElement.dataset.pageTransition = direction;

    let resolveRouteCommit = () => {};
    const routeCommitted = new Promise<void>((resolve) => {
      resolveRouteCommit = resolve;
    });

    const pending: PendingNavigation = {
      pathname: target.pathname,
      timeoutId: 0,
      complete: () => {
        window.clearTimeout(pending.timeoutId);
        if (pendingNavigation.current === pending) pendingNavigation.current = null;
        resolveRouteCommit();
      },
    };
    pendingNavigation.current = pending;
    pending.timeoutId = window.setTimeout(pending.complete, 1800);

    const cleanup = () => {
      pending.complete();
      if (activeTransitionId.current === transitionId) {
        delete document.documentElement.dataset.pageTransition;
      }
    };

    try {
      const transition = document.startViewTransition(() => {
        router.push(href);
        return routeCommitted;
      });
      void transition.finished.then(cleanup, cleanup);
    } catch {
      cleanup();
      router.push(href);
    }
  }, [pathname, router]);

  return <PageNavigationContext.Provider value={navigate}>{children}</PageNavigationContext.Provider>;
}

type PageTransitionLinkProps = Omit<ComponentProps<typeof Link>, "href" | "onNavigate" | "transitionTypes"> & {
  direction: PageTransitionDirection;
  href: string;
};

export function PageTransitionLink({ direction, href, ...props }: PageTransitionLinkProps) {
  const navigate = useContext(PageNavigationContext);

  return (
    <Link
      {...props}
      href={href}
      onNavigate={(event) => {
        if (!navigate) return;
        event.preventDefault();
        navigate(href, direction);
      }}
    />
  );
}
