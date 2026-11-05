# Tool Calling & Verifiable Agents Specification (Meta Model API)

Tanto `POST /v1/chat/completions` como `POST /v1/responses` soportan Function/Tool Calling para construir bucles de agentes verificables.

## Flujo en `POST /v1/chat/completions`

1. **Definición de la herramienta (`verify_kinematic_safety`):**
   - El request incluye `"tools": [{"type": "function", "function": {"name": "verify_kinematic_safety", "description": "...", "parameters": { "type": "object", "properties": {...}, "required": [...] }}}]` y `"tool_choice": "auto"`.
   - Se utiliza `role: "developer"` para las instrucciones de alto nivel y se mantiene el muestreo nativo del modelo de razonamiento.

2. **Turno 1 — Propuesta del Agente:**
   - Cuando el modelo decide llamar a la herramienta, devuelve `finish_reason: "tool_calls"` y dentro de `message.tool_calls[]` entrega `id`, `function.name` y `function.arguments` (JSON string con los waypoints en metros).

3. **Ejecución Determinista Local (`KinematicSafetyKernel`):**
   - El backend en Java evalúa cada segmento $[W_i \to W_{i+1}]$ contra los polígonos métricos proyectados por homografía y verifica la distancia de frenado:
     $$d_{\text{stop}} = \frac{v^2}{2 \mu g} + v \cdot t_{\text{react}}$$
   - Si algún segmento viola el margen mínimo $d_{\min}$, construye un contraejemplo físico con las coordenadas exactas de colisión y el desplazamiento requerido.

4. **Turno 2 — Replanificación Guiada por Contraejemplo:**
   - El servidor agrega a `messages[]` el mensaje `assistant` completo (incluyendo `tool_calls`) seguido de un mensaje:
     ```json
     {
       "role": "tool",
       "tool_call_id": "<call.id>",
       "content": "{\"approved\":false,\"counterexample\":\"REJECTED: Segment [W1 -> W2] passes at 0.28m from Obstacle...\"}"
     }
     ```
   - El modelo lee el contraejemplo físico y emite la ruta corregida que aprueba `verify_kinematic_safety` (`APPROVED`).

