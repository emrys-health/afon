/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      // Brand palette lifted from www.emrys.health (Elementor global colors).
      colors: {
        emrys: {
          navy: "#133145", // primary — headings
          ink: "#074058", // text
          teal: "#25C9BA", // secondary
          deepteal: "#167E7E",
          orange: "#F16003", // accent / CTAs
          "orange-dark": "#D95503",
          mist: "#E9F2F2", // light section background
        },
      },
      fontFamily: {
        sans: ['"IBM Plex Sans"', "ui-sans-serif", "system-ui", "sans-serif"],
      },
    },
  },
  plugins: [],
};
