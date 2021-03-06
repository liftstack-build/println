/** {{{
 *  Copyright (c) 2011, Franz Bettag <franz@bett.ag>
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * All advertising materials mentioning features or use of this software
 *       must display the following acknowledgement:
 *       This product includes software developed by the Bettag Systems UG
 *       and its contributors.
 *
 *  THIS SOFTWARE IS PROVIDED BY BETTAG SYSTEMS UG ''AS IS'' AND ANY
 *  EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED. IN NO EVENT SHALL BETTAG SYSTEMS UG BE LIABLE FOR ANY
 *  DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 *  (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *  LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */// }}}

package code.model

import net.liftweb.http.{S, SessionVar}
import net.liftweb.sitemap.Menu
import net.liftweb.util._
import net.liftweb.common._
import net.liftweb.mapper._
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._

import scala.xml._
import java.util.{Date, Calendar}
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format._
import com.foursquare.rogue.Rogue._

import code.lib._


class Tag extends LongKeyedMapper[Tag] with IdPK with ManyToMany {
	def getSingleton = Tag
	override def primaryKeyField = id

	object name extends MappedString(this, 60) {
		override def dbIndexed_? = true
		override def dbNotNull_? = true
	}

	object slug extends MappedString(this, 60) {
		override def dbIndexed_? = true
		override def dbNotNull_? = true
		override def validations = valUnique("Slug must be unique!") _ :: super.validations
		override def setFilter = trim _ :: toLower _ :: HtmlHelpers.slugify _ :: super.setFilter
	}

	object twitter extends MappedString(this, 30)
	object priority extends MappedInt(this)

	object posts extends MappedManyToMany(PostTags, PostTags.tag, PostTags.post, Post)
	
	def userPosts = {
		val allposts = posts.all
		var published = allposts.filter(f => f.publishDate.is != null).sortWith((e1, e2) => (e1.publishDate.is compareTo e2.publishDate.is) > 0)

		User.currentUser match {
			case Full(u: User) =>
				var unpublished = allposts.filter(f => f.publishDate.is == null)
				unpublished ++ published
			case _ =>
				published
		}
	}
	def publicPosts = userPosts.filter(p => ! (p.published && (p.publishDate compareTo new Date) > 0))

	def listPosts = User.currentUser match {
		case Full(u: User) => userPosts
		case _ => publicPosts
	}

}

object Tag extends Tag with LongKeyedMetaMapper[Tag] {
	override def dbTableName = "tags"

}


class Post extends LongKeyedMapper[Post] with IdPK with ManyToMany with JsEffects[Post] with FBDateTimeMapper {
	def getSingleton = Post
	override def primaryKeyField = id

	object name extends MappedString(this, 255) {
		override def dbIndexed_? = false
		override def dbNotNull_? = true
	    override def defaultValue = "New Blog Post"
	}

	object slug extends MappedString(this, 128) {
		override def dbIndexed_? = true
		override def dbNotNull_? = true
		override def validations = valUnique("Slug must be unique!") _ :: preoccupiedSlugs _ :: super.validations
		override def setFilter = trim _ :: toLower _ :: HtmlHelpers.slugify _ :: super.setFilter

		def preoccupiedSlugs(s: String) = {
			if (s.matches("^(admin/?|post/?|stats/?|users(/.*)?|tag(/.*)?)$"))
				List(FieldError(this, "You cannot use a pre-defined slug."))
			else List[FieldError]()		
		}
	}

	object format extends MappedString(this, 20) {
    	override def defaultValue = "markdown"
	}

	object contentCache extends MappedText(this)
	object content extends MappedText(this) {
		override def dbIndexed_? = false
		override def dbNotNull_? = false
	}

	object teaserCache extends MappedText(this)
	object teaser extends MappedText(this) {
		override def dbIndexed_? = false
		override def dbNotNull_? = false
	}
	
	object teaserLink extends MappedString(this, 128) {
		override def dbNotNull_? = false
		override def defaultValue = "Read more..."
	}

	object published extends MappedBoolean(this) {
		override def dbIndexed_? = true
		override def dbNotNull_? = true
		override def defaultValue = false
	}

	object publishInStream extends MappedBoolean(this) {
		override def dbIndexed_? = true
		override def dbNotNull_? = true
		override def defaultValue = true
	}
	
	object showDate extends MappedBoolean(this) {
		override def dbIndexed_? = true
		override def dbNotNull_? = true
		override def defaultValue = true
	}

	object publishDate extends FBMappedDateTime(this)
	
	object updatedAt extends FBMappedUpdatedAt(this)
	
	object createdAt extends FBMappedCreatedAt(this)
	
	object author extends MappedLongForeignKey(this, User)
	
	object tags extends MappedManyToMany(PostTags, PostTags.post, PostTags.tag, Tag)

	def link = "/%s".format(slug)

	def publish(a: Boolean) = {
		this.published(a).save
		this
	}

	def publishAt(d: String): DateTime = try {
		val fmt = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm")
		val dt = fmt.parseDateTime(d)
		this.published(true).publishDate(dt.toDate).save
		dt
	} catch {
		case _ =>
			val res = DateTimeHelpers.getDate
			this.published(true).publishDate(res.toDate).save
			res
	}
	
	def teaserText: NodeSeq =
		try {
			XML.loadString("<span>" + HtmlHelpers.filter(this.teaserCache.is) + "</span>")
		} catch {
			case _ if (User.currentUser != Empty) => this.teaserCache match {
				case _ if (this.teaserCache.is == null || this.teaserCache.is.matches("^(\\s*(\\r|\\n|\\r\\n)\\s*)$")) => <p/>
				case _ => <p>Malformed teaser-body could not be parsed.</p>
			}
			case _ => NodeSeq.Empty
		}
	
	def contentText: NodeSeq =
		try {
			XML.loadString("<span>" + HtmlHelpers.filter(this.contentCache.is) + "</span>")
		} catch {
			case _ if (User.currentUser != Empty) => this.contentCache match {
				case _ if (this.contentCache.is == null || this.contentCache.is.matches("^(\\s*(\\r|\\n|\\r\\n)\\s*)$")) => <p/>
				case _ => <p>Malformed content-body could not be parsed.</p>
			}
			case _ => NodeSeq.Empty
		}

}

object Post extends Post with LongKeyedMetaMapper[Post] {
	override def dbTableName = "posts"

	def all = User.currentUser match {
		case Full(u: User) =>
			Post.findAll(By(Post.published, false), By(Post.publishInStream, true), OrderBy(Post.createdAt, Descending)) ++
			Post.findAll(By(Post.published, true), By(Post.publishInStream, true), OrderBy(Post.publishDate, Descending))
		case _ => Post.findAll(By(Post.published, true), By(Post.publishInStream, true), By_<(Post.publishDate, new Date), OrderBy(Post.publishDate, Descending))
	}

	def sitemap = User.currentUser match {
		case Full(u: User) =>
			Post.findAll(By(Post.published, false), OrderBy(Post.createdAt, Descending)) ++
			Post.findAll(By(Post.published, true), OrderBy(Post.publishDate, Descending))
		case _ => Post.findAll(By(Post.published, true), By_<(Post.publishDate, new Date), OrderBy(Post.publishDate, Descending))
	}

	def one(gNameOrId: String) = {
		val nameOrId = gNameOrId.replaceAll("^/", "")
		User.currentUser match {
			case Full(u: User) =>
				Post.find(By(Post.slug, nameOrId)) match {
					case Full(p: Post) => Full(p)
					case _ => Full(Post.create.author(u).slug(nameOrId).name(nameOrId.replaceAll("-", " ").replaceFirst("^\\d{4}/\\d{2}/", "")))
				}
			case _ => Post.find(By(Post.slug, nameOrId), By(Post.published, true), By_<(Post.publishDate, new Date))
		}
	}

	def lastmod: DateTime = Post.find(OrderBy(Post.updatedAt, Descending)) match {
		case Full(p: Post) => DateTimeHelpers.getDate(p.updatedAt)
		case _ => DateTimeHelpers.getDate
	}

}


class PostTags extends LongKeyedMapper[PostTags] with IdPK  {
  def getSingleton = PostTags

  object post extends MappedLongForeignKey(this, Post)
  object tag extends MappedLongForeignKey(this, Tag)
}


object PostTags extends PostTags with LongKeyedMetaMapper[PostTags]  {
	override def dbTableName = "post_tags"

}
