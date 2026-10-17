"""Generate SYNTHETIC chest imaging studies for RDSS demos and tests.

No real patient data is involved: images are procedural phantoms (ellipsoids with
Hounsfield-like values). Outputs:

  <out>/ct-current/   axial chest CT phantom with a right-lung nodule and left pleural effusion
  <out>/ct-prior/     earlier CT of the same phantom without the effusion (for comparison)
  <out>/dx-chest/     single-frame chest radiograph simulated by projecting the CT phantom

Usage: python generate.py --out /out --patient-id MRN100002 [--slices 80]
"""

import argparse
import datetime as dt
import os

import numpy as np
from pydicom.dataset import FileDataset, FileMetaDataset
from pydicom.uid import ExplicitVRLittleEndian, generate_uid

CT_IMAGE_STORAGE = "1.2.840.10008.5.1.4.1.1.2"
DX_IMAGE_STORAGE = "1.2.840.10008.5.1.4.1.1.1.1"  # Digital X-Ray Image Storage - For Presentation

ROWS = COLS = 256
PIXEL_MM = 1.4
SLICE_MM = 2.5


def phantom(slices: int, with_effusion: bool, with_nodule: bool, rng: np.random.Generator) -> np.ndarray:
    """Returns a (z, y, x) volume in Hounsfield units."""
    z, y, x = np.mgrid[0:slices, 0:ROWS, 0:COLS].astype(np.float32)
    cz, cy, cx = slices / 2, ROWS / 2, COLS / 2
    vol = np.full((slices, ROWS, COLS), -1000.0, dtype=np.float32)

    body = ((x - cx) / 112) ** 2 + ((y - cy) / 82) ** 2 <= 1
    vol[body] = 40  # soft tissue

    zn = (z - cz) / (slices * 0.48)
    for side in (-1, 1):  # two lungs, tapering towards apex and base
        lung = ((x - (cx + side * 48)) / 38) ** 2 + ((y - (cy - 4)) / 58) ** 2 + zn ** 2 <= 1
        vol[lung] = -850

    heart = ((x - (cx + 12)) / 30) ** 2 + ((y - (cy + 14)) / 26) ** 2 + ((z - cz * 0.85) / (slices * 0.22)) ** 2 <= 1
    vol[heart] = 45
    spine = ((x - cx) / 11) ** 2 + ((y - (cy + 62)) / 11) ** 2 <= 1
    vol[spine] = 700

    if with_nodule:  # ~9 mm solid nodule in the right lung (patient right = image left)
        r = 9 / 2 / PIXEL_MM
        nodule = ((x - (cx - 52)) / r) ** 2 + ((y - (cy - 20)) / r) ** 2 + ((z - cz * 1.15) / (r * PIXEL_MM / SLICE_MM)) ** 2 <= 1
        vol[nodule] = 30

    if with_effusion:  # dependent (posterior) fluid layer in the left lung, lower third
        left_lung = ((x - (cx + 48)) / 38) ** 2 + ((y - (cy - 4)) / 58) ** 2 + zn ** 2 <= 1
        effusion = left_lung & (y > cy + 28) & (z < slices * 0.4)
        vol[effusion] = 15

    vol += rng.normal(0, 12, vol.shape).astype(np.float32)
    return vol


def base_dataset(path: str, sop_class: str, sop_uid: str) -> FileDataset:
    meta = FileMetaDataset()
    meta.MediaStorageSOPClassUID = sop_class
    meta.MediaStorageSOPInstanceUID = sop_uid
    meta.TransferSyntaxUID = ExplicitVRLittleEndian
    meta.ImplementationClassUID = generate_uid()
    ds = FileDataset(path, {}, file_meta=meta, preamble=b"\0" * 128)
    ds.SOPClassUID = sop_class
    ds.SOPInstanceUID = sop_uid
    return ds


def patient_and_study(ds, args, study_uid: str, study_date: dt.date, description: str, accession: str):
    ds.PatientName = "SYNTHETIC^PHANTOM"
    ds.PatientID = args.patient_id
    ds.PatientBirthDate = ""
    ds.PatientSex = "O"
    ds.StudyInstanceUID = study_uid
    ds.StudyDate = study_date.strftime("%Y%m%d")
    ds.StudyTime = "101500"
    ds.StudyDescription = description
    ds.AccessionNumber = accession
    ds.StudyID = accession[-6:]
    ds.ReferringPhysicianName = ""
    ds.InstitutionName = "RDSS SYNTHETIC DATA"
    ds.BodyPartExamined = "CHEST"


def write_ct(args, out_dir: str, study_date: dt.date, with_effusion: bool, accession: str, rng) -> None:
    os.makedirs(out_dir, exist_ok=True)
    vol = phantom(args.slices, with_effusion, with_nodule=True, rng=rng)
    stored = np.clip(vol + 1024, 0, 4095).astype(np.uint16)
    study_uid, series_uid, for_uid = generate_uid(), generate_uid(), generate_uid()
    desc = "SYNTHETIC CT CHEST" + (" - CURRENT" if with_effusion else " - PRIOR")
    for i in range(args.slices):
        sop_uid = generate_uid()
        path = os.path.join(out_dir, f"ct_{i:03d}.dcm")
        ds = base_dataset(path, CT_IMAGE_STORAGE, sop_uid)
        patient_and_study(ds, args, study_uid, study_date, desc, accession)
        ds.Modality = "CT"
        ds.SeriesInstanceUID = series_uid
        ds.SeriesNumber = 1
        ds.SeriesDescription = "Axial 2.5mm SYNTHETIC PHANTOM"
        ds.FrameOfReferenceUID = for_uid
        ds.InstanceNumber = i + 1
        ds.ImageType = ["DERIVED", "SECONDARY", "AXIAL"]
        z_pos = -i * SLICE_MM
        ds.ImagePositionPatient = [-(COLS * PIXEL_MM) / 2, -(ROWS * PIXEL_MM) / 2, z_pos]
        ds.ImageOrientationPatient = [1, 0, 0, 0, 1, 0]
        ds.SliceLocation = z_pos
        ds.SliceThickness = SLICE_MM
        ds.SpacingBetweenSlices = SLICE_MM
        ds.PixelSpacing = [PIXEL_MM, PIXEL_MM]
        ds.Rows, ds.Columns = ROWS, COLS
        ds.SamplesPerPixel = 1
        ds.PhotometricInterpretation = "MONOCHROME2"
        ds.BitsAllocated, ds.BitsStored, ds.HighBit = 16, 12, 11
        ds.PixelRepresentation = 0
        ds.RescaleIntercept = -1024
        ds.RescaleSlope = 1
        ds.RescaleType = "HU"
        ds.WindowCenter = [-600, 40]
        ds.WindowWidth = [1500, 400]
        ds.WindowCenterWidthExplanation = ["LUNG", "MEDIASTINUM"]
        # Slice 0 is the apex (top of the chest); flip z so slices run head -> feet.
        ds.PixelData = stored[args.slices - 1 - i].tobytes()
        ds.save_as(path, enforce_file_format=True)


def write_dx(args, out_dir: str, study_date: dt.date, accession: str, rng) -> None:
    os.makedirs(out_dir, exist_ok=True)
    vol = phantom(args.slices, with_effusion=True, with_nodule=True, rng=rng)
    mu = np.clip((vol + 1000) / 1000, 0, None) * 0.02  # crude attenuation per voxel
    projection = mu.sum(axis=1)  # AP projection -> (z, x)
    img = 1 - np.exp(-projection)
    img = (img - img.min()) / (img.max() - img.min())
    # Resample to a portrait 512 x 512 image, apex at the top.
    zi = np.linspace(args.slices - 1, 0, 512).astype(int)
    xi = np.linspace(0, COLS - 1, 512).astype(int)
    img = img[np.ix_(zi, xi)]
    pixels = (img * 4095).astype(np.uint16)
    sop_uid = generate_uid()
    path = os.path.join(out_dir, "dx_pa.dcm")
    ds = base_dataset(path, DX_IMAGE_STORAGE, sop_uid)
    patient_and_study(ds, args, generate_uid(), study_date, "SYNTHETIC CHEST RADIOGRAPH PA", accession)
    ds.Modality = "DX"
    ds.SeriesInstanceUID = generate_uid()
    ds.SeriesNumber = 1
    ds.SeriesDescription = "PA SYNTHETIC PROJECTION"
    ds.InstanceNumber = 1
    ds.ImageType = ["DERIVED", "SECONDARY"]
    ds.PresentationIntentType = "FOR PRESENTATION"
    ds.ViewPosition = "PA"
    ds.ImagerPixelSpacing = [0.7, 0.7]
    ds.PixelSpacing = [0.7, 0.7]
    ds.Rows, ds.Columns = 512, 512
    ds.SamplesPerPixel = 1
    ds.PhotometricInterpretation = "MONOCHROME2"
    ds.BitsAllocated, ds.BitsStored, ds.HighBit = 16, 12, 11
    ds.PixelRepresentation = 0
    ds.WindowCenter, ds.WindowWidth = 2048, 4096
    ds.PixelData = pixels.tobytes()
    ds.save_as(path, enforce_file_format=True)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out", required=True)
    p.add_argument("--patient-id", default="SYNTHETIC-0001")
    p.add_argument("--slices", type=int, default=80)
    p.add_argument("--seed", type=int, default=42)
    args = p.parse_args()
    rng = np.random.default_rng(args.seed)
    today = dt.date.today()
    write_ct(args, os.path.join(args.out, "ct-current"), today, True, "SYNCT0000002", rng)
    write_ct(args, os.path.join(args.out, "ct-prior"), today - dt.timedelta(days=180), False, "SYNCT0000001", rng)
    write_dx(args, os.path.join(args.out, "dx-chest"), today, "SYNDX0000001", rng)
    print(f"Wrote synthetic studies for patient id {args.patient_id} to {args.out}")


if __name__ == "__main__":
    main()
