// Real-Chromium reflow probe (not a jsdom viewport assertion).
// Start web/test/recovery-upload-workspace.mobile.fixtures.mjs, then start the web app with
// PAPER_T_RAIL_API_ORIGIN=http://127.0.0.1:8081. In a named agent-browser session open
// http://127.0.0.1:3000/analysis-runs/run-mobile?view=recovery at 390x844, expand
// "View recorded PDF-rights declaration", and run:
// AGENT_BROWSER_ENGINE=chrome agent-browser --session <session> eval --stdin < web/test/recovery-upload-workspace.mobile.browser.js
(() => {
  const viewportWidth = window.innerWidth;
  if (viewportWidth !== 390) {
    throw new Error(`Expected a 390px mobile viewport, received ${viewportWidth}px.`);
  }

  const findTextElement = (selector, predicate, description) => {
    const element = [...document.querySelectorAll(selector)].find(predicate);
    if (!element) throw new Error(`${description} is not rendered in the mobile workspace.`);
    return element;
  };
  const elements = {
    bibliographyKey: findTextElement("p", (element) => element.textContent.includes("Bibliography Entry · mobile-reference-"), "Long Bibliography Entry key"),
    identifier: findTextElement("span", (element) => element.textContent.startsWith("10.1234/mobile-layout-"), "Long DOI identifier"),
    rightsDisclosure: findTextElement("p", (element) => element.textContent.includes("LONG_DISCLOSURE_START") && element.textContent.includes("LONG_DISCLOSURE_END"), "Long recorded rights disclosure"),
    history: findTextElement("p", (element) => element.textContent.includes("Outcome MOBILE_HISTORY_"), "Long recovery history event"),
  };

  const measured = Object.entries(elements).map(([name, element]) => {
    const bounds = element.getBoundingClientRect();
    if (bounds.width <= 0 || bounds.height <= 0 || bounds.left < -1 || bounds.right > viewportWidth + 1) {
      throw new Error(`${name} is clipped or outside the mobile viewport: ${JSON.stringify({ left: bounds.left, right: bounds.right, width: bounds.width })}`);
    }
    if (element.scrollWidth > element.clientWidth + 1) {
      throw new Error(`${name} overflows its rendered text box: ${JSON.stringify({ scrollWidth: element.scrollWidth, clientWidth: element.clientWidth })}`);
    }
    return { name, left: Math.round(bounds.left), right: Math.round(bounds.right), width: Math.round(bounds.width), height: Math.round(bounds.height) };
  });

  const pageWidth = document.documentElement.scrollWidth;
  if (pageWidth > viewportWidth) {
    throw new Error(`The mobile page has horizontal overflow: ${pageWidth}px content in ${viewportWidth}px viewport.`);
  }
  return { passed: true, viewportWidth, pageWidth, measured };
})()
