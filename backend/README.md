# Max AI backend (Cloudflare Workers)

1. Cloudflare account (email only), then Workers & Pages > Create > Create Worker. Name it `max-ai`, Deploy.
2. Edit code: paste all of worker.js, Deploy.
3. Settings > Variables and Secrets, add:
   - ACCESS_CODE (Secret): a long phrase only you know. The app sends it with every request.
   - GEMINI_API_KEY (Secret): from https://aistudio.google.com/apikey
   - GEMINI_MODEL (Text): e.g. gemini-2.5-flash (change here when Google renames models, no app update needed)
   - optional backup provider: OPENROUTER_API_KEY (Secret), OPENROUTER_MODEL (Text)
4. Open https://max-ai.<your-subdomain>.workers.dev/ : it should show {"ok":true,"service":"max-ai"}.
5. In the app: Profile > gear > Settings > Max AI: address + access code > save > test.
