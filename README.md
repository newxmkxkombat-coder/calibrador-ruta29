# Calibrador Ruta 29 (Neiva)

App para ir pendiente del tiempo en los puntos de control de la Ruta 29:
Caña Brava, Polideportivo, Las Vegas, Caldo Parao, Antonio Nariño y Palmas.

- Cada día pregunta la hora, el punto de arranque (Caña Brava o Palmas) y la planilla.
- Marca sola cada punto con el GPS y dice si vas adelantado o atrasado (meta: 2 a 3 min de adelanto).
- Avisa por voz, también con la pantalla apagada (app de Android).
- Muestra las 24 planillas de los compañeros (de a 5 minutos entre carros).
- Guarda el resumen del día con el adelanto o retraso en cada punto.

## Partes
- `index.html`: la pantalla. La app de Android la carga desde GitHub Pages, así que los cambios aquí llegan solos.
- `android/`: el código de la app de Android (el "motor" de GPS y voz en segundo plano).
- `calibrador-ruta29.apk`: la app lista para instalar.
