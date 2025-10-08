// login elements
const login = document.querySelector(".login");
const loginForm = login.querySelector(".login__form");
const loginInput = login.querySelector(".login__input");

// security code elements
const securityCodeSection = document.querySelector(".security-code");
const securityCodeForm = securityCodeSection.querySelector(".security-code__form");
const securityCodeInput = securityCodeSection.querySelector(".security-code__input");

// chat elements
const chat = document.querySelector(".chat");
const chatForm = chat.querySelector(".chat__form");
const chatInput = chat.querySelector(".chat__input");
const chatMessages = chat.querySelector(".chat__messages");

const colors = [
    "cadetblue", "darkgoldenrod", "cornflowerblue",
    "darkkhaki", "hotpink", "gold"
];

const user = { id: "", name: "", color: "" };
let websocket;
let heartbeatInterval;

// -- ELEMENTS PARA O POPUP DO CADEADO --
const lockPopup = document.getElementById("lockPopup");
const lockCodeConfirm = document.getElementById("lockCodeConfirm");
const lockCodeCancel = document.getElementById("lockCodeCancel");
const lockCodeError = document.getElementById("lockCodeError");
const lockCodeInputs = lockPopup.querySelectorAll(".code-input");

// Variável para armazenar a mensagem bloqueada atual (se precisar desbloquear depois)
let lockedMessageDiv = null;

// ---------------------- MESSAGE ELEMENTS ----------------------
const createMessageSelfElement = (content) => {
    const div = document.createElement("div");
    div.classList.add("message--self");
    div.innerHTML = content;
    return div;
};

const createMessageOtherElement = (content, sender, senderColor) => {
    const div = document.createElement("div");
    const span = document.createElement("span");
    div.classList.add("message--other");

    span.classList.add("message--sender");
    span.style.color = senderColor;
    span.innerHTML = sender;

    const lock = document.createElement("span");
    lock.classList.add("lock-icon");
    lock.innerHTML = " 🔒";
    lock.title = "Mensagem protegida";
    lock.style.cursor = "pointer";

    // Evento para abrir popup
    lock.addEventListener("click", () => {
        lockedMessageDiv = div;
        lockPopup.style.display = "flex";

        const firstInput = lockPopup.querySelector(".code-input");
        if (firstInput) firstInput.focus();

        resetLockPopup();
    });

    span.appendChild(lock);
    div.appendChild(span);

    const contentNode = document.createTextNode(content);
    div.appendChild(contentNode);

    return div;
};

const resetLockPopup = () => {
    lockCodeError.style.display = "none";
    lockCodeInputs.forEach(input => input.value = "");
    lockCodeInputs[0].focus();
};

const getRandomColor = () => colors[Math.floor(Math.random() * colors.length)];

const scrollScreen = () => {
    window.scrollTo({ top: document.body.scrollHeight, behavior: "smooth" });
};

// ---------------------- WEBSOCKET ----------------------
const connectWebSocket = () => {
    websocket = new WebSocket(`${location.origin.replace(/^http/, "ws")}/ws`);

    websocket.onopen = () => {
        console.log("✅ Connected to server");

        // Heartbeat ping every 30s
        heartbeatInterval = setInterval(() => {
            if (websocket.readyState === WebSocket.OPEN) {
                websocket.send(JSON.stringify({ type: "ping" }));
            }
        }, 30000);
    };

    websocket.onmessage = (event) => {
        const { type, userId, userName, userColor, content } = JSON.parse(event.data);

        // Ignore ping messages
        if (type === "ping") return;

        const message =
            userId === user.id
                ? createMessageSelfElement(content)
                : createMessageOtherElement(content, userName, userColor);

        chatMessages.appendChild(message);
        scrollScreen();
    };

    websocket.onclose = () => {
        console.log("⚠️ Disconnected from server, retrying in 3s...");
        clearInterval(heartbeatInterval);
        setTimeout(connectWebSocket, 3000); // auto-reconnect
    };

    websocket.onerror = (err) => {
        console.error("WebSocket error:", err);
        websocket.close();
    };
};

// ---------------------- LOGIN ----------------------
const handleLogin = (event) => {
    event.preventDefault();

    user.id = crypto.randomUUID();
    user.name = loginInput.value;
    user.color = getRandomColor();

    // Oculta a tela de login e exibe a tela de código de segurança
    login.style.display = "none";
    securityCodeSection.style.display = "block";
};

const handleSecurityCode = (event) => {
    event.preventDefault();
    securityCodeSection.style.display = "none";
    chat.style.display = "flex";
};

// ---------------------- SEND MESSAGE ----------------------
const sendMessage = (event) => {
    event.preventDefault();
    if (!websocket || websocket.readyState !== WebSocket.OPEN) return;

    const message = {
        userId: user.id,
        userName: user.name,
        userColor: user.color,
        content: chatInput.value
    };

    websocket.send(JSON.stringify(message));
    chatInput.value = "";
};

// ---------------------- POPUP LOCK BUTTONS ----------------------

// Botão cancelar fecha o popup
lockCodeCancel.addEventListener("click", () => {
    resetLockPopup();
    lockPopup.style.display = "none";
});

// Botão confirmar valida o código
lockCodeConfirm.addEventListener("click", (e) => {
    e.preventDefault();
    const code = Array.from(lockCodeInputs).map(input => input.value).join("");

    const correctCode = "123456"; // Defina seu código aqui

    if (code === correctCode) {
        alert("Código correto! Mensagem desbloqueada.");

        // Exemplo: Remove o cadeado da mensagem para desbloquear visualmente
        if (lockedMessageDiv) {
            const lockIcon = lockedMessageDiv.querySelector(".lock-icon");
            if (lockIcon) lockIcon.remove();
        }

        resetLockPopup();
        lockPopup.style.display = "none";
        lockedMessageDiv = null;
    } else {
        lockCodeError.style.display = "block";
    }
});

// ---------------------- EVENT LISTENERS ----------------------

connectWebSocket();
loginForm.addEventListener("submit", handleLogin);
securityCodeForm.addEventListener("submit", handleSecurityCode);
chatForm.addEventListener("submit", sendMessage);
