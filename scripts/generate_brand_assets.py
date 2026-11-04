"""Generate the independently drawn two-column ledger mark. Standard library only."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
INK = '#24594A'
PAPER = '#F6F1E6'
FOLD = '#AACAAF'
MARK = ('M9,4H15V28H9Q4,28 4,23V9Q4,4 9,4Z '
        'M18,4H22L28,10V23Q28,28 23,28H18Z '
        'M8,10H12V12H8Z M8,16H12V18H8Z M8,22H12V24H8Z '
        'M21,14H25V16H21Z M21,20H25V22H21Z')
CORNER = 'M22,4V8Q22,10 24,10H28Z'

def write(relative, text):
    path = RES / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text + '\n', encoding='utf-8', newline='\n')
def vector(size, viewport, paths):
    return (f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{size}dp" '
            f'android:height="{size}dp" android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n'
            + paths + '\n</vector>')
def mark(color):
    return f'<path android:fillColor="{color}" android:fillType="evenOdd" android:pathData="{MARK}" />'
group = '<group android:scaleX="2.1" android:scaleY="2.1" android:translateX="20.4" android:translateY="20.4">'
write('drawable/ic_launcher_foreground.xml', vector(108, 108, group + mark(PAPER) +
      f'<path android:fillColor="{FOLD}" android:pathData="{CORNER}" /></group>'))
write('drawable/ic_launcher_monochrome.xml', vector(108, 108, group + mark('#FFFFFFFF') + '</group>'))
write('drawable/ic_brand_mark.xml', vector(32, 32, mark('#FFFFFFFF')))
write('drawable/ic_brand_notification.xml', vector(24, 24,
      '<group android:scaleX="0.8333333" android:scaleY="0.8333333" android:translateX="-1.3333333" android:translateY="-1.3333333">' + mark('#FFFFFFFF') + '</group>'))
for directory, themed in [('mipmap-anydpi', False), ('mipmap-anydpi-v33', True)]:
    write(directory + '/ic_launcher.xml',
          '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools" tools:ignore="MonochromeLauncherIcon">\n'
          '    <background android:drawable="@color/brand_ink" />\n'
          '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n' +
          ('    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n' if themed else '    <!-- API 33+ uses the version-qualified file with the monochrome layer. -->\n') +
          '</adaptive-icon>')
write('values/brand_colors.xml', f'<resources>\n    <color name="brand_ink">{INK}</color>\n</resources>')
source = ROOT / 'design/brand/mark.svg'
source.parent.mkdir(parents=True, exist_ok=True)
source.write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">\n'
                  f'<rect width="108" height="108" fill="{INK}"/>\n'
                  f'<g transform="translate(20.4 20.4) scale(2.1)"><path fill="{PAPER}" fill-rule="evenodd" d="{MARK}"/>'
                  f'<path fill="{FOLD}" d="{CORNER}"/></g>\n</svg>\n', encoding='utf-8', newline='\n')
print('Generated launcher, monochrome, notification and in-app vectors.')
