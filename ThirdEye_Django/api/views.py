import os
import torch
from django.conf import settings
from django.http import JsonResponse
from django.views.decorators.csrf import csrf_exempt
from PIL import Image
from transformers import BlipProcessor, BlipForConditionalGeneration
from ultralytics import YOLO

print("Loading BLIP Model...")
MODEL_PATH = os.path.join(settings.BASE_DIR, 'local_blip_model')
blip_processor = BlipProcessor.from_pretrained(MODEL_PATH)
blip_model = BlipForConditionalGeneration.from_pretrained(MODEL_PATH)

print("Loading YOLOv8 Model...")
yolo_model = YOLO('yolov8n.pt') 

print("Both Models Loaded! API is ready.")
torch.set_num_threads(6)

@csrf_exempt
def predict_caption(request):
    if request.method == 'POST' and request.FILES.get('image'):
        try:
            image_file = request.FILES['image']
            raw_image = Image.open(image_file).convert('RGB')
            
            # Check what mode the Android app is in!
            mode = request.POST.get('mode', 'scene')
            
            caption = ""
            
            # 1. BLIP (Skip this completely if we are doing a high-speed radar scan)
            if mode != 'radar':
                inputs = blip_processor(raw_image, return_tensors="pt")
                with torch.no_grad():
                    out = blip_model.generate(**inputs, max_new_tokens=30)
                caption = blip_processor.decode(out[0], skip_special_tokens=True)
            
            # 2. YOLO (Always run for spatial awareness and radar)
            results = yolo_model(raw_image, verbose=False)
            boxes = results[0].boxes
            
            distance_text = "Clear path ahead."
            spatial_text = ""
            yolo_objects = []
            unique_directions = []
            
            if len(boxes) > 0:
                largest_area = 0
                detected_directions = []
                
                for box in boxes:
                    x1, y1, x2, y2 = box.xyxyn[0].tolist()
                    area = (x2 - x1) * (y2 - y1) 
                    if area > largest_area: largest_area = area
                    
                    center_x = (x1 + x2) / 2.0
                    class_id = int(box.cls[0])
                    label = yolo_model.names[class_id]
                    
                    yolo_objects.append(label)
                    
                    if center_x < 0.33: direction = "on the left"
                    elif center_x > 0.66: direction = "on the right"
                    else: direction = "in the center"
                        
                    detected_directions.append(f"{label} {direction}")
                
                if largest_area > 0.50: distance_text = "Caution, an object is very close."
                elif largest_area > 0.15: distance_text = "An object is a few steps away."
                else: distance_text = "Objects are at a safe distance."
                    
                unique_directions = list(dict.fromkeys(detected_directions))
                spatial_text = " I also see: " + ", ".join(unique_directions) + "."

            # Return the data in organized buckets so Android can choose what to read
            return JsonResponse({
                'caption': caption,
                'distance': distance_text,
                'spatial': spatial_text,
                'objects': yolo_objects,
                'directions_list': unique_directions
            })
            
        except Exception as e:
            return JsonResponse({'error': str(e)}, status=500)
            
    return JsonResponse({'error': 'Send POST with image'}, status=400)