# rumahku reconstruction backend — GPU job service (Brush trainer).
#
# Deploy on a cloud GPU host with nvidia-container-runtime ("--gpus all" /
# CDI), which injects the matched NVIDIA driver userspace. Brush renders via
# Vulkan (wgpu), so we need the Vulkan loader + the X11 client libs that
# libGLX_nvidia depends on, and — critically — the "graphics" driver
# capability so the runtime injects the Vulkan ICD (not just CUDA/compute).
#
# Build:  docker build -t rumahku-backend backend/     (needs backend/brush-cli)
# Run:    docker run --gpus all -p 8000:8000 -v rumahku-jobs:/data/jobs rumahku-backend
FROM nvidia/cuda:12.6.2-runtime-ubuntu24.04

# python3 + Vulkan/X11 for Brush; numpy + colmap (CPU SIFT) for the optional
# ?refine=colmap pose-prior path (backend/colmap_refine.py). colmap here is the
# CPU build, which is what colmap_refine.py asks for (--use_gpu 0).
RUN apt-get update && apt-get install -y --no-install-recommends \
      python3 python3-numpy colmap libvulkan1 libx11-6 libxext6 ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Request the Vulkan-capable driver injection (graphics), not just compute.
ENV NVIDIA_VISIBLE_DEVICES=all \
    NVIDIA_DRIVER_CAPABILITIES=compute,graphics,utility

# The Brush trainer binary (x86-64, built from the brush fork). Ship it in the
# image, or bind-mount it at run time.
COPY brush-cli          /app/brush-cli
COPY serve.py           /app/serve.py
COPY colmap_refine.py   /app/colmap_refine.py
COPY rumahku_credctl.py /app/rumahku_credctl.py
RUN chmod +x /app/brush-cli

# In-container GPU execution (no distrobox host-exec on a real GPU host).
ENV BRUSH_CLI=/app/brush-cli \
    JOBS_DIR=/data/jobs \
    PORT=8000 \
    HOST_EXEC="" \
    MAX_CONCURRENT=1
EXPOSE 8000
CMD ["python3", "/app/serve.py"]
