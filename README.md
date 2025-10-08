# CriptografiaJava

This is a real-time chat application using a **Java WebSocket backend** and a **JavaScript frontend**.  
The project is structured to run on **GitHub Codespaces**, but it can also run locally.

---

## 🗂 Project Structure
chat-app/
├── frontend/ # Frontend (HTML/CSS/JS + Vite)
│ ├── index.html
│ ├── assets/
│ │ ├── css/
│ │ └── js/
│ ├── package.json
│ └── vite.config.js
├── backend/ # Java WebSocket backend
│ ├── pom.xml
│ └── src/main/java/chat/
│ ├── ChatServer.java
│ └── ChatEndpoint.java
└── README.md


---

## ⚡ Prerequisites

- **Node.js** (v18+) for frontend
- **Java 17+** and **Maven** for backend
- GitHub Codespaces is optional but recommended for easy hosting/testing.

---

## 🚀 Setup Instructions

### 1️⃣ Start the backend (Java WebSocket server)

1. Open a terminal in the `backend/` folder:

```bash
cd backend

Build and run the server using Maven:

mvn clean compile exec:java


You should see:

✅ Chat server running on ws://localhost:8080/ws
<sub>(Leave this terminal open while testing the frontend.)</sub>

```

### 2️⃣ Start the frontend (HTML/JS)

Open a new terminal in the frontend/ folder:
```

cd frontend


Install dependencies:

npm install


Start the Node development server:

npm start

```

The terminal will show a URL like:
```

  Local:   http://localhost:3000/
  Network: https://abcdef1234-3000.preview.app.github.dev/
```

### Open the Public URL to access the chat from any device.

Backend (8080) → Private

Frontend (3000) → Public