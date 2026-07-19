(function () {
    "use strict";

    const thread = document.getElementById("thread");
    const composerForm = document.getElementById("composerForm");
    const messageInput = document.getElementById("messageInput");
    const invoiceImageInput = document.getElementById("invoiceImageInput");
    const attachmentPreview = document.getElementById("attachmentPreview");
    const attachmentName = document.getElementById("attachmentName");
    const clearAttachmentBtn = document.getElementById("clearAttachment");
    const userIdInput = document.getElementById("userIdInput");
    const newSessionBtn = document.getElementById("newSessionBtn");
    const sessionListEl = document.getElementById("sessionList");
    const invoiceListEl = document.getElementById("invoiceList");

    let sessionId = crypto.randomUUID();
    let pendingImage = null;

    function currentUserId() {
        return userIdInput.value.trim() || "demo-user";
    }

    // ---------- Rendering ----------

    function appendEntry({ fromUser, text, data, audioUrl }) {
        const entry = document.createElement("div");
        entry.className = "ledger-entry " + (fromUser ? "ledger-entry--user" : "ledger-entry--bot");

        const stamp = document.createElement("div");
        stamp.className = "ledger-entry__stamp";
        stamp.textContent = fromUser ? "YOU" : "FIN";
        entry.appendChild(stamp);

        const body = document.createElement("div");
        body.className = "ledger-entry__body";

        const p = document.createElement("p");
        p.textContent = text;
        body.appendChild(p);

        if (data && (data.invoiceNumber || data.amount)) {
            body.appendChild(renderInvoiceCard(data));
        }

        if (audioUrl) {
            const audio = document.createElement("audio");
            audio.controls = true;
            audio.src = audioUrl;
            body.appendChild(audio);
        }

        entry.appendChild(body);
        thread.appendChild(entry);
        thread.scrollTop = thread.scrollHeight;
    }

    function renderInvoiceCard(invoice) {
        const card = document.createElement("div");
        card.className = "invoice-card";

        const header = document.createElement("div");
        header.className = "invoice-card__header";

        const title = document.createElement("span");
        title.className = "invoice-card__title";
        title.textContent = "Invoice " + (invoice.invoiceNumber || "—");
        header.appendChild(title);

        const stampClass = invoice.status && /fail/i.test(invoice.status)
            ? "status-stamp--failed"
            : invoice.status && /(paid|captured|succeeded)/i.test(invoice.status)
                ? "status-stamp--success"
                : "status-stamp--pending";
        const statusStamp = document.createElement("span");
        statusStamp.className = "status-stamp " + stampClass;
        statusStamp.textContent = invoice.status || "parsed";
        header.appendChild(statusStamp);

        card.appendChild(header);

        const rows = [
            ["Amount", invoice.amount ? invoice.currency + " " + invoice.amount : "—"],
            ["Payee", invoice.payeeName || "—"],
            ["Date", invoice.invoiceDate || "—"],
            ["Parse confidence", invoice.parseConfidence != null ? Math.round(invoice.parseConfidence * 100) + "%" : "—"],
        ];
        rows.forEach(([label, value]) => {
            const row = document.createElement("div");
            row.className = "invoice-card__row";
            row.innerHTML = `<span class="label">${label}</span><span class="value"></span>`;
            row.querySelector(".value").textContent = value;
            card.appendChild(row);
        });

        return card;
    }

    // ---------- Sending messages ----------

    async function sendMessage(text) {
        appendEntry({ fromUser: true, text: text || "[invoice image]" });

        const formData = new FormData();
        formData.append("userId", currentUserId());
        formData.append("sessionId", sessionId);
        if (text) formData.append("message", text);
        if (pendingImage) formData.append("invoiceImage", pendingImage);

        try {
            const res = await fetch("/api/chat", { method: "POST", body: formData });
            const payload = await res.json();
            appendEntry({
                fromUser: false,
                text: payload.message,
                data: payload.data,
                audioUrl: payload.audioUrl,
            });
        } catch (err) {
            appendEntry({ fromUser: false, text: "Network error — could not reach the server. " + err });
        } finally {
            clearAttachment();
            refreshSidebar();
        }
    }

    composerForm.addEventListener("submit", (e) => {
        e.preventDefault();
        const text = messageInput.value.trim();
        if (!text && !pendingImage) return;
        sendMessage(text);
        messageInput.value = "";
    });

    invoiceImageInput.addEventListener("change", () => {
        const file = invoiceImageInput.files[0];
        if (!file) return;
        pendingImage = file;
        attachmentName.textContent = file.name;
        attachmentPreview.hidden = false;
        if (!messageInput.value.trim()) {
            messageInput.value = "scan invoice";
        }
    });

    clearAttachmentBtn.addEventListener("click", clearAttachment);

    function clearAttachment() {
        pendingImage = null;
        invoiceImageInput.value = "";
        attachmentPreview.hidden = true;
    }

    // ---------- Sidebar ----------

    newSessionBtn.addEventListener("click", () => {
        sessionId = crypto.randomUUID();
        thread.innerHTML = "";
        appendEntry({ fromUser: false, text: "Started a new conversation. Say \"help\" any time to see the available commands." });
        refreshSidebar();
    });

    async function refreshSidebar() {
        try {
            const [sessionsRes, invoicesRes] = await Promise.all([
                fetch("/api/chat/sessions?userId=" + encodeURIComponent(currentUserId())),
                fetch("/api/chat/invoices?userId=" + encodeURIComponent(currentUserId())),
            ]);
            const sessions = await sessionsRes.json();
            const invoices = await invoicesRes.json();
            renderSessionList(sessions);
            renderInvoiceList(invoices);
        } catch (err) {
            console.warn("Could not refresh sidebar", err);
        }
    }

    function renderSessionList(sessions) {
        sessionListEl.innerHTML = "";
        sessions.forEach((sid) => {
            const li = document.createElement("li");
            li.textContent = sid.substring(0, 8) + "…";
            li.title = sid;
            li.className = sid === sessionId ? "active" : "";
            li.addEventListener("click", () => loadSession(sid));
            sessionListEl.appendChild(li);
        });
    }

    function renderInvoiceList(invoices) {
        invoiceListEl.innerHTML = "";
        invoices.slice(0, 10).forEach((inv) => {
            const li = document.createElement("li");
            const label = document.createElement("span");
            label.textContent = inv.invoiceNumber || "—";
            const amount = document.createElement("span");
            amount.className = "invoice-amount";
            amount.textContent = inv.amount ? (inv.currency + " " + inv.amount) : "";
            li.appendChild(label);
            li.appendChild(amount);
            invoiceListEl.appendChild(li);
        });
    }

    async function loadSession(sid) {
        sessionId = sid;
        thread.innerHTML = "";
        try {
            const res = await fetch("/api/chat/sessions/" + encodeURIComponent(sid));
            const messages = await res.json();
            messages.forEach((m) => {
                let data = null;
                if (m.dataJson) {
                    try { data = JSON.parse(m.dataJson); } catch (e) { /* ignore */ }
                }
                appendEntry({ fromUser: m.fromUser, text: m.content, data, audioUrl: m.audioUrl });
            });
        } catch (err) {
            console.warn("Could not load session", err);
        }
        refreshSidebar();
    }

    // Initial load
    refreshSidebar();
})();
