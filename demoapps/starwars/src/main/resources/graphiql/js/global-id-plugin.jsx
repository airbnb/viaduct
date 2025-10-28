/**
 * Global ID Plugin for GraphiQL 5
 * Provides encode/decode functionality for Global IDs
 * Fully React-based plugin compatible with GraphiQL 5 plugin system
 */

// Global ID utilities (based on Airbnb's implementation)
function createGlobalId(typeName, internalId) {
  if (!typeName || internalId === null || internalId === undefined) {
    throw new Error('Both typeName and internalId are required');
  }
  const combined = `${typeName}:${encodeURIComponent(String(internalId))}`;
  return btoa(combined);
}

function parseGlobalId(globalId) {
  if (!globalId || typeof globalId !== 'string') {
    throw new Error('Global ID must be a non-empty string');
  }

  try {
    const decoded = atob(globalId);
    const colonIndex = decoded.indexOf(':');

    if (colonIndex === -1) {
      throw new Error('Invalid Global ID format: missing colon separator');
    }

    const typeName = decoded.substring(0, colonIndex);
    const internalId = decodeURIComponent(decoded.substring(colonIndex + 1));

    if (!typeName) {
      throw new Error('Invalid Global ID: empty type name');
    }

    return { typeName, internalId };
  } catch (error) {
    if (error.message.includes('Invalid Global ID')) {
      throw error;
    }
    throw new Error(`Failed to decode Global ID: ${error.message}`);
  }
}

// Copy to clipboard utility
function copyToClipboard(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    return navigator.clipboard.writeText(text);
  } else {
    // Fallback for older browsers
    const textarea = document.createElement('textarea');
    textarea.value = text;
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand('copy');
    document.body.removeChild(textarea);
    return Promise.resolve();
  }
}

// React component for Global ID Plugin using JSX
function GlobalIdPluginContent(React) {
  const [mode, setMode] = React.useState('decode'); // 'decode' or 'encode'
  const [input, setInput] = React.useState('');
  const [output, setOutput] = React.useState('');
  const [error, setError] = React.useState('');
  const [copySuccess, setCopySuccess] = React.useState('');

  const handleProcess = React.useCallback(() => {
    setError('');
    setOutput('');
    setCopySuccess('');

    if (!input.trim()) {
      setError('Please enter a value to process');
      return;
    }

    try {
      if (mode === 'decode') {
        const { typeName, internalId } = parseGlobalId(input.trim());
        setOutput(`Type: ${typeName}\nInternal ID: ${internalId}`);
      } else {
        // Encode mode - expect format "TypeName:InternalId"
        const parts = input.trim().split(':', 2);
        if (parts.length !== 2 || !parts[0] || !parts[1]) {
          throw new Error('Please enter in format "TypeName:InternalId" (e.g., "Character:1")');
        }
        const globalId = createGlobalId(parts[0], parts[1]);
        setOutput(globalId);
      }
    } catch (err) {
      setError(err.message);
    }
  }, [mode, input]);

  const handleCopy = React.useCallback(() => {
    if (!output) return;

    copyToClipboard(output)
      .then(() => {
        setCopySuccess('Copied!');
        setTimeout(() => setCopySuccess(''), 2000);
      })
      .catch(() => {
        setCopySuccess('Copy failed');
        setTimeout(() => setCopySuccess(''), 2000);
      });
  }, [output]);

  const handleModeToggle = React.useCallback(() => {
    const newMode = mode === 'decode' ? 'encode' : 'decode';

    // Try to intelligently convert the current input to the new mode
    let newInput = '';

    if (input.trim()) {
      try {
        if (mode === 'decode' && output) {
          // Currently in decode mode with output, use the decoded result for encode mode
          const lines = output.split('\n');
          const typeLine = lines.find(line => line.startsWith('Type: '));
          const idLine = lines.find(line => line.startsWith('Internal ID: '));

          if (typeLine && idLine) {
            const type = typeLine.replace('Type: ', '');
            const id = idLine.replace('Internal ID: ', '');
            newInput = `${type}:${id}`;
          }
        } else if (mode === 'encode' && output) {
          // Currently in encode mode with output, use the encoded result for decode mode
          newInput = output;
        } else {
          // No conversion possible, keep original input
          newInput = input;
        }
      } catch (err) {
        // If conversion fails, just keep the original input
        newInput = input;
      }
    }

    setMode(newMode);
    setInput(newInput);
    setOutput('');
    setError('');
    setCopySuccess('');
  }, [mode, input, output]);

  React.useEffect(() => {
    if (input.trim()) {
      handleProcess();
    } else {
      setOutput('');
      setError('');
    }
  }, [input, mode, handleProcess]);

  return (
    <div
      className="global-id-plugin"
      style={{
        padding: '16px',
        fontFamily: 'system-ui, -apple-system, sans-serif',
        fontSize: '14px',
        height: '100%',
        overflow: 'auto'
      }}
    >
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: '12px'
      }}>
        <h3 style={{
          margin: '0',
          fontSize: '16px',
          fontWeight: '600',
          color: '#333'
        }}>
          Global ID Utils
        </h3>
        <button
          onClick={handleModeToggle}
          style={{
            padding: '4px 8px',
            border: '1px solid #ccc',
            borderRadius: '4px',
            backgroundColor: '#fff',
            cursor: 'pointer',
            fontSize: '12px'
          }}
        >
          {mode === 'decode' ? 'Switch to Encode' : 'Switch to Decode'}
        </button>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <div>
          <label style={{
            display: 'block',
            marginBottom: '4px',
            fontWeight: '500',
            color: '#555'
          }}>
            {mode === 'decode' ? 'Global ID:' : 'Type:Internal ID:'}
          </label>
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={mode === 'decode' ? 'e.g., Q2hhcmFjdGVyOjE=' : 'e.g., Character:1'}
            style={{
              width: '100%',
              padding: '6px 8px',
              border: '1px solid #ccc',
              borderRadius: '4px',
              fontSize: '13px',
              fontFamily: 'monospace',
              boxSizing: 'border-box'
            }}
          />
        </div>

        {output && (
          <div style={{ marginTop: '8px' }}>
            <label style={{
              display: 'block',
              marginBottom: '4px',
              fontWeight: '500',
              color: '#555'
            }}>
              Result:
            </label>
            <div style={{ position: 'relative' }}>
              <textarea
                value={output}
                readOnly
                rows={mode === 'decode' ? 2 : 1}
                style={{
                  width: '100%',
                  padding: '6px 8px',
                  border: '1px solid #ccc',
                  borderRadius: '4px',
                  fontSize: '13px',
                  fontFamily: 'monospace',
                  backgroundColor: '#f9f9f9',
                  resize: 'none',
                  boxSizing: 'border-box'
                }}
              />
              <button
                onClick={handleCopy}
                style={{
                  position: 'absolute',
                  top: '4px',
                  right: '4px',
                  padding: '2px 6px',
                  border: '1px solid #ccc',
                  borderRadius: '3px',
                  backgroundColor: '#fff',
                  cursor: 'pointer',
                  fontSize: '11px'
                }}
              >
                {copySuccess || 'Copy'}
              </button>
            </div>
          </div>
        )}

        {error && (
          <div style={{
            padding: '8px',
            backgroundColor: '#fee',
            border: '1px solid #fcc',
            borderRadius: '4px',
            color: '#c33',
            fontSize: '12px'
          }}>
            {error}
          </div>
        )}
      </div>
    </div>
  );
}

// Plugin definition for GraphiQL 5
export function createGlobalIdPlugin(React) {
  return {
    title: 'Global ID Utils',
    icon: () => React.createElement('svg', {
      height: '1em',
      viewBox: '0 0 24 24',
      fill: 'none',
      xmlns: 'http://www.w3.org/2000/svg',
      'aria-hidden': 'true'
    }, [
      React.createElement('title', { key: 'title' }, 'Global ID Utils'),
      React.createElement('path', {
        key: 'key-path',
        d: 'M15.75 5.25a3 3 0 013 3m3 0a6 6 0 01-7.029 5.912c-.563-.097-1.159.026-1.563.43L10.5 17.25H8.25v2.25H6v2.25H2.25v-2.818c0-.597.237-1.17.659-1.591l6.499-6.499c.404-.404.527-1 .43-1.563A6 6 0 1121.75 8.25z',
        stroke: 'currentColor',
        strokeWidth: '1.5',
        strokeLinecap: 'round',
        strokeLinejoin: 'round',
        fill: 'none'
      })
    ]),
    content: () => GlobalIdPluginContent(React)
  };
};