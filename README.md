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

## ⚠️ Important: Machine Learning Models Setup

**CRITICAL:** Due to GitHub's strict file size limit of 100 MB, the heavy machine learning models required for this project are **not** included in this repository. You must download them manually before running the server.

1. Download the `model.safetensors` file (approx. 940 MB) from [HuggingFace (Salesforce/blip-image-captioning-base)](https://huggingface.co/Salesforce/blip-image-captioning-base/resolve/main/model.safetensors).
2. Create a folder named `local_blip_model` inside your `ThirdEye_Django` directory (if it doesn't already exist).
3. Place the downloaded file exactly here:
   `ThirdEye_Django/local_blip_model/model.safetensors`

*(Note: The YOLOv8 Nano weights will download automatically the first time you start the server).*

---

## 💻 Backend Setup (Django Server)

### 1. Installation
Clone the repository and set up your Python virtual environment:
```bash
git clone [https://github.com/neerajsuresh05/ThirdEye.git](https://github.com/neerajsuresh05/ThirdEye.git)
cd ThirdEye/ThirdEye_Django

# Create and activate virtual environment
python -m venv env
.\env\Scripts\activate  # Windows
# source env/bin/activate  # Mac/Linux

# Install required dependencies
pip install django torch torchvision transformers ultralytics pillow
