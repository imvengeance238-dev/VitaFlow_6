// Max AI backend for Cloudflare Workers (free plan, no cold start, no card needed).
// The AI provider keys live here as secrets. They are never inside the Android app.
//
// POST /v1/chat  (Authorization: Bearer <ACCESS_CODE>)
//   { system, messages:[{role:"user"|"model", text}], json, temperature, maxTokens, images:[{mime,data}] }
//   -> { text, provider }
// Tries Gemini first, then OpenRouter if Gemini fails. Change models/keys here without updating the app.

const MAX_BODY = 6 * 1024 * 1024;

function reply(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json; charset=utf-8" } });
}

function clean(body) {
  if (!body || !Array.isArray(body.messages) || body.messages.length === 0) return { error: "messages missing" };
  const messages = body.messages.slice(-30).map((m) => ({
    role: m.role === "model" ? "model" : "user",
    text: String(m.text ?? "").slice(0, 12000),
  }));
  const images = Array.isArray(body.images)
    ? body.images.slice(0, 4).filter((i) => i && typeof i.data === "string").map((i) => ({ mime: String(i.mime || "image/jpeg"), data: i.data }))
    : [];
  return {
    system: String(body.system ?? "").slice(0, 24000),
    messages,
    images,
    json: !!body.json,
    temperature: Math.min(1.5, Math.max(0, Number(body.temperature ?? 0.4))),
    maxTokens: Math.min(2000, Math.max(16, Number(body.maxTokens ?? 700))),
  };
}

async function callGemini(env, r) {
  const model = env.GEMINI_MODEL || "gemini-2.5-flash";
  const contents = r.messages.map((m, i) => {
    const parts = [{ text: m.text }];
    if (i === r.messages.length - 1 && m.role === "user") {
      for (const img of r.images) parts.push({ inline_data: { mime_type: img.mime, data: img.data } });
    }
    return { role: m.role, parts };
  });
  const generationConfig = { temperature: r.temperature, maxOutputTokens: r.maxTokens };
  if (r.json) generationConfig.responseMimeType = "application/json";
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
    body: JSON.stringify({ systemInstruction: { parts: [{ text: r.system }] }, contents, generationConfig }),
    signal: AbortSignal.timeout(25000),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`${res.status} ${data?.error?.message ?? ""}`.trim());
  const parts = data?.candidates?.[0]?.content?.parts ?? [];
  const text = parts.map((p) => p.text ?? "").join("");
  if (!text) throw new Error("empty answer");
  return text;
}

async function callOpenRouter(env, r) {
  const model = env.OPENROUTER_MODEL || "meta-llama/llama-3.3-70b-instruct:free";
  const messages = [{ role: "system", content: r.system }].concat(
    r.messages.map((m) => ({ role: m.role === "model" ? "assistant" : "user", content: m.text }))
  );
  const payload = { model, messages, temperature: r.temperature, max_tokens: r.maxTokens };
  if (r.json) payload.response_format = { type: "json_object" };
  const res = await fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${env.OPENROUTER_API_KEY}`, "X-Title": "Vita Flow Max" },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(30000),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`${res.status} ${data?.error?.message ?? ""}`.trim());
  const text = data?.choices?.[0]?.message?.content ?? "";
  if (!text) throw new Error("empty answer");
  return text;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") return reply({ ok: true, service: "max-ai" });
    if (request.method !== "POST" || url.pathname !== "/v1/chat") return reply({ error: { message: "not found" } }, 404);

    const auth = request.headers.get("Authorization") || "";
    if (!env.ACCESS_CODE || auth !== `Bearer ${env.ACCESS_CODE}`) return reply({ error: { message: "unauthorized" } }, 401);

    const len = Number(request.headers.get("Content-Length") || 0);
    if (len > MAX_BODY) return reply({ error: { message: "too large" } }, 413);
    let body;
    try {
      body = await request.json();
    } catch {
      return reply({ error: { message: "bad json" } }, 400);
    }
    const r = clean(body);
    if (r.error) return reply({ error: { message: r.error } }, 400);

    const problems = [];
    if (env.GEMINI_API_KEY) {
      try {
        return reply({ text: await callGemini(env, r), provider: "gemini" });
      } catch (e) {
        problems.push(`gemini: ${e.message}`);
      }
    }
    if (env.OPENROUTER_API_KEY) {
      try {
        return reply({ text: await callOpenRouter(env, r), provider: "openrouter" });
      } catch (e) {
        problems.push(`openrouter: ${e.message}`);
      }
    }
    return reply({ error: { message: problems.join(" | ") || "no AI provider is configured" } }, 502);
  },
};
