# ThirdEye: Smart Assistive AI System

ThirdEye is an innovative assistive technology designed to act as a digital intermediary for individuals with severe visual impairments or total blindness. By offloading heavy mathematical processing to a dedicated local server, the user's mobile phone is freed to act solely as a high-speed sensory input (camera) and output (audio speaker) device. 

This project bridges the informational gap left by traditional tools (like white canes) by providing rich, contextual awareness of the user's surroundings in real-time.

---

## 🚀 Features
* **Zero-Shot Scene Understanding:** Utilizes the BLIP foundation model to understand the visual context of a room and provide rich, natural-language descriptive sentences.
* **Real-Time Object Detection:** Leverages the YOLOv8 Nano model to mathematically evaluate bounding boxes of objects, translating spatial data into immediate awareness.
* **Edge-to-Server Architecture:** Prevents smartphone thermal throttling and battery depletion by moving heavy Convolutional Neural Networks (CNNs) and Vision Transformers to a local compute node (Django).
* **Audio Feedback:** Prevents cognitive overload by delivering synthesized speech feedback at an optimal rate.

---

## 🛠️ Tech Stack
* **Frontend (Client):** Android Application (Java/XML, OkHttp)
* **Backend (Server):** Django (Python)
* **Machine Learning:** * YOLOv8 Nano (Ultralytics) for fast, lightweight object detection.
  * BLIP (Bootstrapping Language-Image Pre-training) for image captioning.

---

## 💻 Backend Setup (Django Server)

### 1. Installation
First, clone the repository and set up your Python virtual environment:
```bash
git clone [https://github.com/neerajsuresh05/ThirdEye.git](https://github.com/neerajsuresh05/ThirdEye.git)
cd ThirdEye/ThirdEye_Django

# Create and activate virtual environment
python -m venv env
.\env\Scripts\activate  # Windows
# source env/bin/activate  # Mac/Linux

# Install required dependencies
pip install django torch torchvision transformers ultralytics pillow
