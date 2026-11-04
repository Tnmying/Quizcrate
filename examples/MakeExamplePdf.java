import java.io.File;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * Generates examples/offlinequiz-example.pdf with self-written questions only.
 *
 * Font: a local CJK TrueType/OpenType font is required (default NotoSerifSC-VF.ttf on Windows).
 * The font is not part of this repository; the generated PDF embeds only the used glyph subset.
 * Build/run: examples/generate-example-pdf.ps1
 */
public final class MakeExamplePdf {

    private static final float MARGIN = 56f;
    private static final float SIZE = 11.5f;
    private static final float LINE_HEIGHT = 22f;

    private final PDPageContentStream stream;
    private final PDType0Font font;
    private float y;

    private MakeExamplePdf(PDPageContentStream stream, PDType0Font font, float y) {
        this.stream = stream;
        this.font = font;
        this.y = y;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: MakeExamplePdf <cjk-font.ttf> <output.pdf>");
            System.exit(2);
        }
        File fontFile = new File(args[0]);
        File output = new File(args[1]);
        if (!fontFile.isFile()) throw new IllegalArgumentException("font not found: " + fontFile);
        if (output.getParentFile() != null) output.getParentFile().mkdirs();

        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType0Font font = PDType0Font.load(document, fontFile);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                MakeExamplePdf writer = new MakeExamplePdf(
                        content,
                        font,
                        page.getMediaBox().getHeight() - MARGIN
                );
                writer.write();
            }
            document.save(output);
        }
        System.out.println("wrote " + output.getAbsolutePath());
    }

    private void write() throws Exception {
        text("离线题库 04A 自制五题型验收样例", 15f);
        text("说明：全部题目为自制内容，仅用于解析/界面/备份验收。", 9.5f);
        y -= 8;

        section("一、单选题");
        text("1. 水在标准大气压下的沸点是（A）。", SIZE);
        text("A. 100℃    B. 0℃    C. 50℃    D. 200℃", SIZE);

        section("二、多选题");
        text("2. 下列属于可再生能源的是（AC）。", SIZE);
        text("A. 风能    B. 煤    C. 太阳能    D. 石油", SIZE);

        section("三、判断题");
        text("3. 地球是围绕太阳公转的行星。（√）", SIZE);

        section("四、填空题");
        // One underline spans the whole answer including the enumeration comma; the app splits
        // learning blanks on 、 only, so this is the required enumeration sub-blank case.
        String q4 = "4. 中国古代四大发明包括火药、指南针、印刷术和造纸术。";
        text(q4, SIZE);
        underlinePhrase(q4, "火药、指南针、印刷术");
        y -= 4;

        // Explicit cross-line underline: "阳" ends line 1 and "光" starts line 2; both segments
        // touch the line edges so the parser can merge them, while the original line break stays.
        String q5a = "5. 植物需要阳";
        String q5b = "光才能生长。";
        text(q5a, SIZE);
        underlineLast(q5a, "阳");
        text(q5b, SIZE);
        underlinePrefix(q5b, "光");
        y -= 4;

        // Simple layout that intentionally needs review: no underline, so it must be flagged for
        // manual correction without being silently turned into a valid blank.
        text("6. 待校对：请补全这句话____。", SIZE);

        section("五、简答题");
        text("7. 简述绿色植物光合作用对生态系统的意义。", SIZE);
        text("参考答案：制造有机物，释放氧气，并参与碳氧平衡。", SIZE);
    }

    private void section(String title) throws Exception {
        y -= 6;
        text(title, 13f);
        y -= 2;
    }

    private void text(String value, float size) throws Exception {
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(MARGIN, y);
        stream.showText(value);
        stream.endText();
        y -= LINE_HEIGHT;
    }

    private void underlinePhrase(String line, String phrase) throws Exception {
        float prefix = width(line.substring(0, line.indexOf(phrase)));
        float start = MARGIN + prefix;
        underline(start, start + width(phrase));
    }

    private void underlineLast(String line, String suffix) throws Exception {
        float prefix = width(line.substring(0, line.length() - suffix.length()));
        underline(MARGIN + prefix, MARGIN + prefix + width(suffix));
    }

    private void underlinePrefix(String line, String prefix) throws Exception {
        underline(MARGIN, MARGIN + width(prefix));
    }

    private void underline(float x0, float x1) throws Exception {
        stream.setStrokingColor(0f, 0f, 0f);
        stream.setLineWidth(0.8f);
        stream.moveTo(x0, y + LINE_HEIGHT - 3f);
        stream.lineTo(x1, y + LINE_HEIGHT - 3f);
        stream.stroke();
    }

    private float width(String value) throws Exception {
        return font.getStringWidth(value) / 1000f * SIZE;
    }
}
