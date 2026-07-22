param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
)

# Generates only new v0.8.1 frames. Existing animation PNGs are never overwritten.
Add-Type -AssemblyName System.Drawing
$outDir = Join-Path $ProjectRoot 'app/src/main/res/drawable-nodpi'
$sourceDir = Join-Path $outDir 'cat_stretching'
$idle = Join-Path $outDir 'cat_idle_01.png'

$csharp = @'
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
public static class CatFrameGenerator {
    public static void Blink(string source, string[] outputs) {
        using (var original = new Bitmap(source)) {
            for (var i = 0; i < outputs.Length; i++) {
                using (var frame = new Bitmap(original.Width, original.Height, PixelFormat.Format32bppArgb)) {
                    using (var g = Graphics.FromImage(frame)) {
                        g.CompositingMode = CompositingMode.SourceCopy;
                        g.DrawImageUnscaled(original, 0, 0);
                        if (i > 0 && i < outputs.Length - 1) {
                            var face = Color.FromArgb(255, 255, 254, 241);
                            using (var fill = new SolidBrush(face)) {
                                var height = i == 2 ? 19 : 14;
                                g.FillEllipse(fill, 188, 205, 29, 30);
                                g.FillEllipse(fill, 280, 205, 29, 30);
                            }
                            using (var pen = new Pen(Color.FromArgb(255, 93, 56, 36), 4f)) {
                                pen.StartCap = LineCap.Round;
                                pen.EndCap = LineCap.Round;
                                var y = i == 2 ? 220 : 221;
                                g.DrawLine(pen, 191, y, 214, y);
                                g.DrawLine(pen, 283, y, 306, y);
                            }
                        }
                    }
                    frame.Save(outputs[i], ImageFormat.Png);
                }
            }
        }
    }
}
'@
Add-Type -TypeDefinition $csharp -ReferencedAssemblies System.Drawing

$blinkOutputs = 1..5 | ForEach-Object { Join-Path $outDir ("cat_blinking_{0:D2}.png" -f $_) }
[CatFrameGenerator]::Blink($idle, [string[]]$blinkOutputs)

# No real yawn art exists. Keep a separate, reproducible Yawn-Stretch set
# derived from existing stretching frames for the low-frequency preview behavior.
1..6 | ForEach-Object {
    $source = Join-Path $outDir ("cat_stretching_{0:D2}.png" -f $_)
    $target = Join-Path $outDir ("cat_yawning_{0:D2}.png" -f $_)
    Copy-Item -LiteralPath $source -Destination $target -Force
}

Write-Output "Generated $($blinkOutputs.Count) blink frames and 6 yawn-stretch frames."
