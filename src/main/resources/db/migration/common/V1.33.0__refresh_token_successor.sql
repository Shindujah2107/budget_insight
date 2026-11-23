-- Refresh token successor (V1.33.0): which token replaced this one when it was rotated. When a
-- phone sends a rotated token again because the answer with its replacement never arrived (weak
-- signal, app closed mid-request), the server can tell that from a copied token: the replacement
-- was never used. Additive only; existing rows keep NULL and behave as before.

ALTER TABLE budget_insights.bi_auth_refresh_token ADD COLUMN IF NOT EXISTS replaced_by_id uuid;
