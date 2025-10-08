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
    div.appendChild(span);
    div.innerHTML += content;
    return div;
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

// ---------------------- EVENT LISTENERS ----------------------
loginForm.addEventListener("submit", handleLogin);
securityCodeForm.addEventListener("submit", handleSecurityCode);
chatForm.addEventListener("submit", sendMessage);