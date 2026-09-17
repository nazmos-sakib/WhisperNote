package app.naz.whispernote.ui.theme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
private val LightColors=lightColorScheme(primary=Color(0xFF526654),onPrimary=Color.White,primaryContainer=Color(0xFFD6E8D1),secondaryContainer=Color(0xFFE8EBD9),tertiaryContainer=Color(0xFFF3E3CD),surface=Color(0xFFFFFBF5),background=Color(0xFFFFFBF5),surfaceContainerHigh=Color(0xFFEDEAE3))
private val DarkColors=darkColorScheme(primary=Color(0xFFB4CDB0),onPrimary=Color(0xFF203524),primaryContainer=Color(0xFF354D39),secondaryContainer=Color(0xFF373D32),tertiaryContainer=Color(0xFF4C4031),surface=Color(0xFF151814),background=Color(0xFF151814),surfaceContainerHigh=Color(0xFF292D27))
@Composable fun WhisperNoteTheme(darkTheme: Boolean=isSystemInDarkTheme(),content:@Composable ()->Unit) { MaterialTheme(colorScheme=if(darkTheme) DarkColors else LightColors,typography=Typography,content=content) }
