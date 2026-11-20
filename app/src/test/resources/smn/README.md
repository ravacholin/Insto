# Fixtures del SMN

- `tiepre-20261120.zip`: respuesta **real** de
  `https://ssl.smn.gob.ar/dpd/zipopendata.php?dato=tiepre`, descargada el 20/11/2026
  a las 13:00 (hora de Buenos Aires) con una petición GET normal. Se guarda tal cual: un
  ZIP con `estado_tiempo20261120.txt` en Windows-1252, CRLF y sin encabezado. Contiene
  filas de las 09:00, 11:00, 12:00 y dos del 19/11 a las 21:00.
- `forecast-4864.unverified.json`: **no verificado**. La portada del SMN respondió
  HTTP 403 «Attention Required! | Cloudflare» a una petición normal, así que no se
  obtuvo el token anónimo ni una respuesta real de `ws1`. El JSON se construyó con la
  estructura que describen clientes independientes (`updated`, `location`, `type`,
  `forecast[]` con `date` y los períodos `early_morning`, `morning`, `afternoon`,
  `night`, cada uno objeto o `null`, con `rain_prob_range: [min, max]`). Los valores
  son de ejemplo. Incluye a propósito un período `null`, uno sin `rain_prob_range`
  (pero con `rain06h`) y uno con `rain_prob_range: null`.

Ningún fixture contiene tokens.
