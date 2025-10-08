// ---------------------- ELEMENTS ----------------------
const login = document.querySelector(".login");
const loginForm = login.querySelector(".login__form");
const loginInput = login.querySelector(".login__input");

const securityCodeSection = document.querySelector(".security-code");
const securityCodeForm = securityCodeSection.querySelector(".security-code__form");
const securityCodeInputs = securityCodeSection.querySelectorAll(".code-input");

const chat = document.querySelector(".chat");
const chatForm = chat.querySelector(".chat__form");
const chatInput = chat.querySelector(".chat__input");
const chatMessages = chat.querySelector(".chat__messages");

const lockPopup = document.getElementById("lockPopup");
const lockCodeConfirm = document.getElementById("lockCodeConfirm");
const lockCodeCancel = document.getElementById("lockCodeCancel");
const lockCodeError = document.getElementById("lockCodeError");
const lockCodeInputs = lockPopup.querySelectorAll(".code-input");

// ---------------------- ROTATING DIGIT CAESAR ----------------------
function shiftChar(c, shift) {
    if (c >= "a" && c <= "z") {
        const base = "a".charCodeAt(0);
        return String.fromCharCode(base + ((c.charCodeAt(0) - base + shift) % 26));
    } else if (c >= "A" && c <= "Z") {
        const base = "A".charCodeAt(0);
        return String.fromCharCode(base + ((c.charCodeAt(0) - base + shift) % 26));
    }
    return c;
}

function encryptWithRotatingDigits(text, password) {
    let out = "";
    const plen = password.length;
    if (plen === 0) return text;
    for (let i = 0; i < text.length; i++) {
        const digit = parseInt(password[i % plen], 10);
        out += shiftChar(text[i], digit);
    }
    return out;
}

function decryptWithRotatingDigits(text, password) {
    let out = "";
    const plen = password.length;
    if (plen === 0) return text;
    for (let i = 0; i < text.length; i++) {
        const digit = parseInt(password[i % plen], 10);
        out += shiftChar(text[i], 26 - (digit % 26));
    }
    return out;
}

// ---------------------- USER & STATE ----------------------
// ----- INIT USER -----
// ---------------------- USER & STATE ----------------------
let user = {
    id: crypto.randomUUID(),           // always new on page load
    name: "",                          // can persist if you want
    color: "",                         // can persist if you want
    password: ""                       // can persist if you want
};

// If you want name/color/password to persist across refreshes, use localStorage
user.name = localStorage.getItem("chat_user_name") || "";
user.color = localStorage.getItem("chat_user_color") || "";
user.password = localStorage.getItem("chat_user_password") || "";


const colors = ["cadetblue", "darkgoldenrod", "cornflowerblue", "darkkhaki", "hotpink", "gold"];
let websocket;
let heartbeatInterval;
let lockedMessageDiv = null;

// ---------------------- MESSAGES ----------------------
function createMessageSelfElement(content) {
    const div = document.createElement("div");
    div.classList.add("message--self");
    div.textContent = content;
    return div;
}

const createMessageOtherElement = (encryptedContent, sender, senderColor, id) => {
    const div = document.createElement("div");
    div.classList.add("message--other");
    div.dataset.messageId = id;
    div.dataset.encryptedContent = encryptedContent;

    const span = document.createElement("span");
    span.classList.add("message--sender");
    span.style.color = senderColor;
    span.textContent = sender;

    const lock = document.createElement("span");
    lock.classList.add("lock-icon");
    lock.innerHTML = " 🔒";
    lock.title = "Mensagem protegida";
    lock.style.cursor = "pointer";

    // clicking the lock opens the unlock popup
    lock.addEventListener("click", () => {
        lockedMessageDiv = div;
        lockPopup.style.display = "flex";
        resetLockPopup();
        lockCodeInputs[0].focus();

    });

    span.appendChild(lock);
    div.appendChild(span);

    // show encrypted content by default
    const contentSpan = document.createElement("span");
    contentSpan.classList.add("message--content")
    contentSpan.textContent = encryptedContent;
    div.appendChild(contentSpan);

    return div;
};


// ---------------------- POPUP ----------------------
function resetLockPopup() {
    lockCodeError.style.display = "none";
    lockCodeInputs.forEach(i => i.value = "");
    lockCodeInputs[0].focus();
    
}

// ---------------------- UTILITIES ----------------------
function getRandomColor() {
    return colors[Math.floor(Math.random() * colors.length)];
}

function scrollScreen() {
    window.scrollTo({ top: document.body.scrollHeight, behavior: "smooth" });
}
let lastMessageId = null;
// ---------------------- WEBSOCKET ----------------------
function connectWebSocket() {
    websocket = new WebSocket(`${location.origin.replace(/^http/, "ws")}/ws`);

    websocket.onopen = () => {
        console.log("✅ Connected");
        console.log(lastMessageId);
        if (user.password && user.id) sendPasswordWhenOpen();
        // send lastMessageId to request only new messages
        if (lastMessageId) {
            return;
        }
    };

    websocket.onmessage = event => {
        const msg = JSON.parse(event.data);

        // Incoming chat messages
        // When receiving a message
        if (msg.type === "message") {
            console.log("set new id to " + msg.id);
            lastMessageId = msg.id;
            const isSelf = msg.ownerId === user.id;

            const element = isSelf
                ? createMessageSelfElement(decryptWithRotatingDigits(msg.encryptedContent, user.password))
                : createMessageOtherElement(msg.encryptedContent, msg.ownerName, msg.userColor, msg.id);

            chatMessages.appendChild(element);
            scrollScreen();
        }


        // Unlock results
        console.log(msg.requesterId)
        console.log(user.id)
        if (msg.type === "internal_delivery" && msg.requesterId === user.id) {
            const payload = JSON.parse(msg.payload);
            if (payload.type === "unlock_result") handleUnlockResult(payload);
        }
    };

    websocket.onclose = () => {
        console.log("⚠️ Disconnected, retrying in 3s...");
        clearInterval(heartbeatInterval);
        setTimeout(connectWebSocket, 3000);
    };

    websocket.onerror = err => {
        console.error("WebSocket error:", err);
        websocket.close();
    };
}

// ---------------------- LOGIN & SECURITY ----------------------
loginForm.addEventListener("submit", e => {
    e.preventDefault();

    if (!user.id) {
        user.id = crypto.randomUUID();
        localStorage.setItem("chat_user_id", user.id);
    }

    user.name = loginInput.value || user.name || "Anon";

    if (!user.color) user.color = getRandomColor();

    localStorage.setItem("chat_user_name", user.name);
    localStorage.setItem("chat_user_color", user.color);

    login.style.display = "none";
    securityCodeSection.style.display = "block";
});


function sendPasswordWhenOpen() {
    const trySend = () => {
        if (websocket.readyState === WebSocket.OPEN) {
            websocket.send(JSON.stringify({
                type: "set_password",
                userId: user.id,
                password: user.password,
                userColor: user.color
            }));
        } else {
            setTimeout(trySend, 150);
        }
    };
    trySend();
}

securityCodeInputs.forEach((input, idx) => {
    // Allow only numbers and move focus
    input.addEventListener("input", () => {
        input.value = input.value.replace(/[^0-9]/g, ""); // numbers only
        if (input.value && idx < securityCodeInputs.length - 1) {
            securityCodeInputs[idx + 1].focus();
        }
    });

    // Backspace moves to previous input
    input.addEventListener("keydown", e => {
        if (e.key === "Backspace" && !input.value && idx > 0) {
            securityCodeInputs[idx - 1].focus();
        }
    });

    // Optional: select all on focus for easy editing
    input.addEventListener("focus", () => input.select());
});

securityCodeForm.addEventListener("submit", e => {
    e.preventDefault();
    user.password = Array.from(securityCodeInputs).map(i => i.value).join("");
    localStorage.setItem("chat_user_password", user.password);
    securityCodeSection.style.display = "none";
    chat.style.display = "flex";

    sendPasswordWhenOpen();
});

// ---------------------- SEND MESSAGE ----------------------
chatForm.addEventListener("submit", e => {
    e.preventDefault();
    if (!user.password) { alert("Set password first!"); return; }
    if (!websocket || websocket.readyState !== WebSocket.OPEN) return;

    websocket.send(JSON.stringify({
        type: "send_message",
        userId: user.id,
        userName: user.name,
        userColor: user.color,
        content: chatInput.value
    }));
    chatInput.value = "";
});

// ---------------------- LOCK POPUP ----------------------

lockCodeInputs.forEach((input, idx) => {
    // Allow only numbers and move focus
    input.addEventListener("input", () => {
        input.value = input.value.replace(/[^0-9]/g, ""); // numbers only
        if (input.value && idx < lockCodeInputs.length - 1) {
            lockCodeInputs[idx + 1].focus();
        }
    });

    // Backspace moves to previous input
    input.addEventListener("keydown", e => {
        if (e.key === "Backspace" && !input.value && idx > 0) {
            lockCodeInputs[idx - 1].focus();
        }
    });

    // Optional: select all on focus for easy editing
    input.addEventListener("focus", () => input.select());
});

lockCodeCancel.addEventListener("click", () => {
    resetLockPopup();
    lockPopup.style.display = "none";
});

lockCodeConfirm.addEventListener("click", e => {
    e.preventDefault();
    const guess = Array.from(lockCodeInputs).map(i => i.value).join("");
    if (!lockedMessageDiv) return;

    const messageId = lockedMessageDiv.dataset.messageId;

    websocket.send(JSON.stringify({
        type: "attempt_unlock",
        requesterId: user.id,
        messageId: messageId,
        guess: guess
    }));

    // immediate visual attempt
    const encrypted = lockedMessageDiv.dataset.encryptedContent;
    const attemptDecrypted = decryptWithRotatingDigits(encrypted, guess);
    let span = lockedMessageDiv.querySelector(".attempted-content");
    if (!span) {
        span = document.createElement("span");
        span.classList.add("attempted-content");
        span.style.color = "darkred";
        lockedMessageDiv.appendChild(span);
    }
    span.textContent = " | " + attemptDecrypted;
});

// ---------------------- HANDLE UNLOCK RESULTS ----------------------
function handleUnlockResult(payload) {
    console.log(payload)
    const messageDiv = Array.from(chatMessages.children)
        .find(d => d.dataset.messageId === payload.messageId);
    if (!messageDiv) return;

    let span = messageDiv.querySelector(".attempted-content");
    let ogMessage = messageDiv.querySelector(".message--content");
    if (!span) {
        span = document.createElement("span");
        span.classList.add("attempted-content");
        messageDiv.appendChild(span);
    }

    if (payload.success) {
        ogMessage.textContent = "";
        span.style.color = "white";
        span.textContent = payload.decryptedContent;
        const lockIcon = messageDiv.querySelector(".lock-icon");
        if (lockIcon) lockIcon.remove();
    } else {
        span.style.color = "red";
        span.textContent = " | " + payload.decryptedContent; // attempted decryption
    }

    resetLockPopup();
    lockPopup.style.display = "none";
}

// ---------------------- INIT ----------------------
connectWebSocket();
