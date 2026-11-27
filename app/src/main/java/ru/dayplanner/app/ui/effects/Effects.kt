package ru.dayplanner.app.ui.effects

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.dayplanner.app.R
import ru.dayplanner.app.ui.theme.AppStyle
import kotlin.math.*
import kotlin.random.Random

interface EffectsController { fun onTaskCompleted(allDone:Boolean) }
object NoEffects:EffectsController{override fun onTaskCompleted(allDone:Boolean){}}
val LocalEffects=staticCompositionLocalOf<EffectsController>{NoEffects}
private data class Celebration(val serial:Long,val label:String,val all:Boolean)
private data class Particle(val x:Float,val speed:Float,val wave:Float,val phase:Float)

@Composable
fun EffectsHost(style:AppStyle,content:@Composable ()->Unit){if(style!=AppStyle.EFFECTS){content();return}
    val phrases=stringArrayResource(R.array.praise);val finale=stringResource(R.string.all_done);var event by remember{mutableStateOf<Celebration?>(null)}
    val controller=remember(phrases.contentHashCode(),finale){object:EffectsController{override fun onTaskCompleted(allDone:Boolean){if(ValueAnimator.areAnimatorsEnabled())event=Celebration(System.nanoTime(),if(allDone) finale else phrases.random(),allDone)}}}
    val palette=listOf(MaterialTheme.colorScheme.primary,MaterialTheme.colorScheme.secondary,MaterialTheme.colorScheme.tertiary);val glow=palette.first().copy(alpha=.09f)
    CompositionLocalProvider(LocalEffects provides controller){Box(Modifier.fillMaxSize()){
        Box(Modifier.fillMaxSize().drawWithContent{drawContent();drawRect(Brush.verticalGradient(listOf(glow,Color.Transparent),endY=84.dp.toPx()),size=Size(size.width,minOf(size.height,84.dp.toPx())))}){content()}
        event?.let { e->key(e.serial){val a=remember{Animatable(0f)};val particles=remember{val rnd=Random(e.serial);List(if(e.all)60 else 30){Particle(rnd.nextFloat(),.6f+rnd.nextFloat()*.5f,rnd.nextFloat()*.08f,rnd.nextFloat()*6.28f)}};LaunchedEffect(Unit){a.animateTo(1f,tween(if(e.all)1400 else 1000,easing=LinearEasing));delay(700);if(event==e)event=null};Canvas(Modifier.fillMaxSize()){val t=a.value;particles.forEachIndexed{i,p->val x=(p.x+p.wave*sin(9*t+p.phase))*size.width;val y=(t*t*p.speed-.025f)*size.height;drawCircle(palette[i%3].copy(alpha=(1f-t).coerceIn(0f,1f)),radius=(if(e.all)4.dp else 3.dp).toPx(),center=Offset(x,y))}};Surface(modifier=Modifier.align(Alignment.Center).padding(20.dp),color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.large){Text(e.label,Modifier.padding(18.dp),style=if(e.all)MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall)} }}
    }}
}

/** Наблюдает за нажатием, но не потребляет его: жест обрабатывает сама кнопка. */
fun Modifier.pressBurst():Modifier=composed { if(ru.dayplanner.app.ui.theme.LocalAppStyle.current!=AppStyle.EFFECTS)return@composed this
    var origin by remember{mutableStateOf(Offset.Zero)};var ticket by remember{mutableLongStateOf(0)};val a=remember{Animatable(1f)};val ink=MaterialTheme.colorScheme.primary;LaunchedEffect(ticket){if(ticket!=0L){a.snapTo(0f);a.animateTo(1f,tween(260))}}
    this.pointerInput(Unit){awaitEachGesture {val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial);if(ValueAnimator.areAnimatorsEnabled()){origin=down.position;ticket++}}}.drawWithContent {drawContent();val t=a.value;if(t<1f){val color=ink.copy(alpha=(1f-t)*.65f);val r=(5+17*t).dp.toPx();drawCircle(color,r,origin,style=Stroke(1.5.dp.toPx()));for(i in 0..5){val angle=i*Math.PI/3;drawCircle(color,2.dp.toPx(),origin+Offset(cos(angle).toFloat(),sin(angle).toFloat())*(r+4.dp.toPx()))}} }
}
