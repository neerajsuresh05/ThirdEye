import os
import torch
from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt
from PIL import Image
from transformers import BlipProcessor, BlipForConditionalGeneration
from ultralytics import YOLO

# 1. Load BLIP (Captioning)
print("Loading BLIP Model...")
MODEL_PATH = os.path.join(settings.BASE_DIR, 'local_blip_model')
blip_processor = BlipProcessor.from_pretrained(MODEL_PATH)
blip_model = BlipForConditionalGeneration.from_pretrained(MODEL_PATH)

# 2. Load YOLOv8-Nano (Distance Warning)
# This will auto-download a tiny 6MB file the first time you run it
print("Loading YOLOv8 Model...")
yolo_model = YOLO('yolov8n.pt') 

print("Both Models Loaded! API is ready.")

# Force CPU to use multiple threads to speed things up
torch.set_num_threads(6)

@csrf_exempt
def predict_caption(request):
    if request.method == 'POST' and request.FILES.get('image'):
        try:
            # Read the image sent from Android
            image_file = request.FILES['image']
            raw_image = Image.open(image_file).convert('RGB')
            
            # --- TASK 1: GET THE CAPTION (CPU Diet Mode) ---
            inputs = blip_processor(raw_image, return_tensors="pt")
            
            # no_grad() stops the CPU from storing heavy training memory
            with torch.no_grad():
                out = blip_model.generate(**inputs, max_new_tokens=30)
            caption = blip_processor.decode(out[0], skip_special_tokens=True)
            
            # --- TASK 2: GET THE DISTANCE (YOLOv8 Bounding Boxes) ---
            # Run YOLO on the image
            results = yolo_model(raw_image, verbose=False)
            boxes = results[0].boxes
            
            distance_text = ""
            
            # If YOLO detects objects, let's find the biggest one
            if len(boxes) > 0:
                largest_area = 0
                
                for box in boxes:
                    # xyxyn gives us coordinates from 0.0 to 1.0 (percentages of the screen)
                    x1, y1, x2, y2 = box.xyxyn[0]
                    area = (x2 - x1) * (y2 - y1) # Calculate how much screen it takes up
                    
                    if area > largest_area:
                        largest_area = area
                
                # Convert the size into human speech
                if largest_area > 0.50: # Object takes up more than 50% of the screen
                    distance_text = "Caution, an object is very close."
                elif largest_area > 0.15: # Object takes up 15% to 50%
                    distance_text = "An object is a few steps away."
                else:
                    distance_text = "Clear path ahead."

            # Combine them!
            final_speech = f"{caption}. {distance_text}"
            
            return JsonResponse({'caption': final_speech})
            
        except Exception as e:
            return JsonResponse({'error': str(e)}, status=500)
            
    return JsonResponse({'error': 'Send a POST request with an image file.'}, status=400)