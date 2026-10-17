# Runs the synthetic DICOM generator without installing anything on the host.
#   docker build -t rdss-synthetic-dicom tools/synthetic-dicom
#   docker run --rm -v "${PWD}/tools/synthetic-dicom/out:/out" rdss-synthetic-dicom --out /out --patient-id MRN100002
FROM python:3.12-slim
RUN pip install --no-cache-dir "numpy==2.2.*" "pydicom==3.0.*"
WORKDIR /app
COPY generate.py .
RUN useradd -r -u 1000 gen
USER gen
ENTRYPOINT ["python", "generate.py"]
