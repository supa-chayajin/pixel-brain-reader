package cloud.wafflecommons.pixelbrainreader.data.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cloud.wafflecommons.pixelbrainreader.data.repository.FileRepository
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val fileRepository: FileRepository,
    private val secretManager: cloud.wafflecommons.pixelbrainreader.data.local.security.SecretManager,
    private val geminiRagManager: cloud.wafflecommons.pixelbrainreader.data.ai.GeminiRagManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString("url") ?: return@withContext Result.failure()

        try {
            // 1. Fetch HTML
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .get()

            val title = doc.title() ?: "Untitled Article"
            // Try to find the main content to avoid scraping nav/footer
            val articleBody = doc.select("article, main, .content, .post-content").first() ?: doc.body()

            // 2. Convert to Markdown
            val converter = FlexmarkHtmlConverter.builder().build()
            val markdownBody = converter.convert(articleBody.html())
            
            // 3. AI Summary (Gemini)
            var summary = ""
            try {
                // Heuristic: only summarize if content is substantial
                if (markdownBody.length > 500) {
                     val prompt = "Summarize this article in 3 bullet points (French or English matching content): \n\n${markdownBody.take(4000)}" // limit context
                     val flow = geminiRagManager.generateResponse(prompt, useRAG = false)
                     val sb = StringBuilder()
                     flow.collect { chunk -> 
                         if (!chunk.startsWith("Thinking")) sb.append(chunk) 
                     }
                     summary = sb.toString().trim()
                }
            } catch (e: Exception) {
                summary = "Summary unavailable."
            }

            val finalContent = """
                # $title
                
                **Source:** $url
                **Captured:** ${java.time.LocalDate.now()}
                
                ## AI Summary
                $summary
                
                ## Content
                $markdownBody
            """.trimIndent()

            // 4. Save to Inbox
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9 \\-]"), "").trim().take(50)
            val fileName = "00_Inbox/$sanitizedTitle.md"
            
            // Check if file exists, if so append timestamp
            val finalPath = if (fileRepository.fileExists(fileName)) {
                "00_Inbox/${sanitizedTitle}_${System.currentTimeMillis()}.md"
            } else {
                fileName
            }

            fileRepository.createFile(finalPath, finalContent)
            
            // 5. Git Sync
            val (owner, repo) = secretManager.getRepoInfo()
            if (owner != null && repo != null) {
                fileRepository.pushDirtyFiles(owner, repo, "docs(import): add $sanitizedTitle")
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
