export function getPaginationPagesCount() {
  // Returns the number of pages for a paginated list

  const selector = ".pagination .page";
  const activePage = 1;
  const nextPages = browser.elements(selector).value.length;

  return activePage + nextPages;
}

export function isWarningToastVisible() {
  // Returns if a red error toast is visible in the upper right corner

  const selector = "#alert-container .alert.alert-danger";
  return browser.isVisible(selector);
}
