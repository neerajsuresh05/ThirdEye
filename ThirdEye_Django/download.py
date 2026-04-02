from transformers import BlipProcessor, BlipForConditionalGeneration

print("Downloading model from Hugging Face...")

# 1. Download the processor and model from the internet
processor = BlipProcessor.from_pretrained("Salesforce/blip-image-captioning-base")
model = BlipForConditionalGeneration.from_pretrained(
    "Salesforce/blip-image-captioning-base"
)

# 2. Save them locally into a specific folder in your project
processor.save_pretrained("./local_blip_model")
model.save_pretrained("./local_blip_model")

print("Download complete! Files saved in the 'local_blip_model' folder.")
