/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        vanguard: {
          bg: "#05080f",
          panel: "#0a101d",
          border: "#18263e",
          cyan: "#00f0ff",
          emerald: "#00ff9d",
          amber: "#ffb000",
          crimson: "#ff2a54",
          violet: "#9d5cff",
        },
      },
      fontFamily: {
        mono: ["'JetBrains Mono'", "'Fira Code'", "Consolas", "monospace"],
        sans: ["'Inter'", "system-ui", "sans-serif"],
      },
      boxShadow: {
        neon: "0 0 18px rgba(0, 240, 255, 0.18)",
        crimson: "0 0 22px rgba(255, 42, 84, 0.28)",
      },
    },
  },
  plugins: [],
};
