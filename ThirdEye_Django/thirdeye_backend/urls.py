from django.contrib import admin
from django.urls import path
from api.views import predict_caption

urlpatterns = [
    path('admin/', admin.site.urls),
    path('api/predict/', predict_caption, name='predict'),
]