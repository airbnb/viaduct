/**
 * JSX Loader Utility
 * Fetches and transpiles JSX files using Babel standalone
 */

export async function loadJSX(jsxPath) {
  try {
    // Fetch the JSX file
    const response = await fetch(jsxPath);
    if (!response.ok) {
      throw new Error(`Failed to fetch JSX file: ${response.status}`);
    }
    const jsxCode = await response.text();

    // Transpile JSX to JavaScript using Babel
    const { code } = Babel.transform(jsxCode, {
      presets: [['react', { runtime: 'classic' }]],
      plugins: []
    });

    // Create a blob URL and import the transpiled code
    const blob = new Blob([code], { type: 'application/javascript' });
    const url = URL.createObjectURL(blob);

    try {
      const module = await import(url);
      return module;
    } finally {
      URL.revokeObjectURL(url);
    }
  } catch (error) {
    console.error('Error loading JSX:', error);
    throw error;
  }
}