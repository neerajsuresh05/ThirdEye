import requests

# 1. The URL of your local server
url = 'http://127.0.0.1:8000/api/predict/'

# 2. Open a test image from your computer
# (Change 'dog.jpg' to whatever image file you have in the folder)
files = {'image': open('dog.jpg', 'rb')}

# 3. Send the POST request to Django
print("Sending image to API...")
response = requests.post(url, files=files)

# 4. Print the text the AI sends back!
print("Response:", response.json())