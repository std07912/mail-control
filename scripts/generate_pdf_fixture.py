from pathlib import Path
import os, zlib, hashlib, json, sys
import fitz

folder = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('test-files')
folder.mkdir(parents=True, exist_ok=True)
path = folder / 'pdf_split_test_50MB.pdf'
# Each page owns its own image. All payload is visible page content, with no
# unused padding, attachments, passwords, or real business documents.
width = 1024
heights = ([512, 1024, 640, 960, 832] * 4)[:19]
heights.append(round(50_000_000 / (width * 3) - sum(heights)))
assert all(0 < h < 2000 for h in heights)
objects = {}
objects[1] = b'<< /Type /Catalog /Pages 2 0 R >>'
objects[3] = b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
page_ids = []
def stream(data, attrs=b''):
    return b'<< ' + attrs + b' /Length ' + str(len(data)).encode() + b' >>\nstream\n' + data + b'\nendstream'
for index, height in enumerate(heights):
    page_id = 4 + index * 3
    image_id, content_id = page_id + 1, page_id + 2
    page_ids.append(page_id)
    # Unpredictable pixels avoid compression collapsing the test to a tiny PDF.
    pixels = os.urandom(width * height * 3)
    image = zlib.compress(pixels, 1)
    attrs = f'/Type /XObject /Subtype /Image /Width {width} /Height {height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode'.encode()
    objects[image_id] = stream(image, attrs)
    content = (f'BT /F1 20 Tf 42 795 Td (PDF SPLIT TEST - DUMMY DATA) Tj ET\n'
               f'BT /F1 14 Tf 42 767 Td (Page {index+1:02d} / 20 - original order marker {index+1:02d}) Tj ET\n'
               f'BT /F1 11 Tf 42 742 Td (Synthetic image only. No real business information.) Tj ET\n'
               f'q 511 0 0 590 42 110 cm /Im1 Do Q\n'
               f'BT /F1 12 Tf 42 65 Td (TEST PAGE {index+1:02d} - check this number after splitting.) Tj ET\n').encode()
    objects[content_id] = stream(content)
    objects[page_id] = (f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] '
                        f'/Resources << /Font << /F1 3 0 R >> /XObject << /Im1 {image_id} 0 R >> >> '
                        f'/Contents {content_id} 0 R >>').encode()
objects[2] = ('<< /Type /Pages /Count 20 /Kids [' + ' '.join(f'{x} 0 R' for x in page_ids) + '] >>').encode()
with path.open('wb') as output:
    output.write(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n')
    offsets = [0]
    for number in range(1, max(objects)+1):
        offsets.append(output.tell())
        output.write(f'{number} 0 obj\n'.encode() + objects[number] + b'\nendobj\n')
    xref = output.tell()
    output.write(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
    for offset in offsets[1:]: output.write(f'{offset:010d} 00000 n \n'.encode())
    output.write(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())

with fitz.open(path) as pdf:
    assert len(pdf) == 20 and not pdf.is_encrypted
    page_sizes = []
    for index, page in enumerate(pdf):
        assert f'Page {index+1:02d} / 20' in page.get_text()
        image = page.get_pixmap(matrix=fitz.Matrix(.12,.12))
        assert image.width > 0 and image.height > 0
        # Independent-page serialization, not an estimate based on source size.
        with fitz.open() as single:
            single.insert_pdf(pdf, from_page=index, to_page=index)
            page_sizes.append(len(single.tobytes()))
    assert max(page_sizes) < 9_000_000
    first = pdf[0].get_pixmap(matrix=fitz.Matrix(.6,.6))
    first.save(folder / 'pdf_test_preview.png')
report = dict(filename=path.name,bytes=path.stat().st_size,MB=round(path.stat().st_size/1_000_000,3),pages=20,
              min_page_bytes=min(page_sizes),max_page_bytes=max(page_sizes),
              sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
              verified='Opened and rendered all 20 pages; each page reserialized independently below 9MB. Not an Android device test.')
(folder / 'pdf_test_info.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps(report,ensure_ascii=False))
