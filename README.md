# Dayfile

An Android app to allow you to take notes about your daily life. Please see the [user manual](https://zornslemma.github.io/dayfile-docs) for an overview of the app and how to use it.

Dayfile is released under the [MIT licence](LICENSE). There is a [changelog](CHANGELOG.md).

# Documentation

As noted below, LLM-assisted development created a profusion of documents. All of these probably have some value. Some are likely easier to read for humans than others, but none of them are particularly readable, obviously useful or make me feel proud when I look at them.

Coding and architectural style guidelines:
* [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — the conventions, including Kotlin style, state ownership and testing
* [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
* [docs/AI_GUIDELINES.md](docs/AI_GUIDELINES.md) — how to work on this project with AI assistance, rather than about the app itself
* [docs/ENGINEERING_PHILOSOPHY.md](docs/ENGINEERING_PHILOSOPHY.md) — the values behind those conventions, for when no document covers the question

The product specification:
* [docs/SPEC.md](docs/SPEC.md)

App architecture and design notes, including write-ups on complex/tricky areas:
* [docs/COMPOSE_LIVE_EDITING.md](docs/COMPOSE_LIVE_EDITING.md)
* [docs/DATABASE.md](docs/DATABASE.md)
* [docs/HOME_SCREEN_NOTES.md](docs/HOME_SCREEN_NOTES.md)
* [docs/RESTORE_SAFETY_NOTES.md](docs/RESTORE_SAFETY_NOTES.md)
* [docs/SESSION_STATE_LIFETIMES.md](docs/SESSION_STATE_LIFETIMES.md)
* [docs/STATE_PRESERVATION_AND_PROCESS_DEATH.md](docs/STATE_PRESERVATION_AND_PROCESS_DEATH.md)

Testing limitations and review notes:
* [docs/TESTING_LIMITATIONS_AND_REVIEW.md](docs/TESTING_LIMITATIONS_AND_REVIEW.md) - very LLM

Possible future work:
* [docs/ROADMAP.md](docs/ROADMAP.md) - very LLM
* [TODO.md](TODO.md) - mostly my actual human thoughts, but with some LLM junk

# Development methodology

As an experiment, I created this project with significant AI assistance:

* I wrote an initial SPEC.md and iterated on it using ChatGPT, which also suggested other documents to put in the repo that might be helpful for AI development.

* The bulk of the code was written under my direction using aider and then opencode using a variety of LLMs (more on this below).

* A few small parts of the code were imported from other projects I had written by hand, although even there I had discussed things with ChatGPT and Grok.

* I did make a few small changes to the code directly.

* I had design discussions (MD3 compliance, general UI design, etc) with ChatGPT and Grok. I also pasted some code written via aider into ChatGPT and Grok for discussion and code review.

* A general unhappiness with some of the internal structure led to a largely manual rewrite of some parts (with ChatGPT discussions to assist).

* From that point on, I tended to use more powerful (if still mostly free) models and was more hands off.

The bulk of this work took place in July/August/September 2026.

# LLMs used

I interacted with standard free ChatGPT and Grok via the standard chat interface, copying and pasting some small fragments of code in and out. They served primarily for bouncing ideas off as well as offering expert second opinions on things created by other LLMs. To a lesser extent, I also used the various free LLMs available via duck.ai for similar purposes.

Other LLMs, listed below, were used via aider and (later, after the app itself was mostly code complete) opencode. 

I created an OpenRouter account and credited it with $10 originally. As I finalise this project for its initial release, that account still has over $4 left. Most (but not all) of that $6 was probably spent on this project. I made heavy use of the free tier LLMs available on OpenRouter.

While I had and still have some privacy concerns with all these online LLMs and particularly the free ones, I felt that since I was discussing and creating code I intended to release as open source anyway the trade-off was acceptable. (I have no graphics card. Small local models running on the CPU might have been capable of some useful "junior dev" assistance at acceptable speeds, but for this experiment I wanted to see what relatively powerful models could do.)

After being impressed with the free trial of Ox Alpha (probably the first frontier or at least near-frontier model I had used), I did pay for some GLM 5.3 Flash use. It's very difficult to say but I suspect had I had to pay for GLM 5.3 Flash instead of using Ox Alpha, total LLM costs during development might have been more like $20-30. I did not pay for any monthly subscription services like Claude or Codex.

The following LLMs were used with aider and/or opencode. The descriptions are copied from their model pages on OpenRouter; they are a bit marketing-heavy, but they are also the only details I have, so they're probably worth preserving.

Tencent Hy3:
* Hy3 is a 295B-parameter Mixture-of-Experts model from Tencent (21B active, 192 experts with top-8 routing) built for reasoning, agentic workflows, and real-world production use. It supports a configurable reasoning effort: a direct no-think mode by default, plus low and high chain-of-thought modes for complex math, coding, and multi-step problems. With a 256K context window, Hy3 targets long-horizon tasks, including improved coreference resolution, multi-turn constraint tracking, and stable tool-calling that generalizes across agent scaffoldings.

NVIDIA Nemotron 3 Ultra:
* NVIDIA Nemotron 3 Ultra is an open frontier-reasoning and orchestration model from NVIDIA, with 55B active parameters out of 550B total (MoE). Built on a hybrid Transformer-Mamba mixture-of-experts architecture, it supports text input and output with a context window of up to 1M tokens. It is suited for long-running agentic workflows, including agent orchestration, coding agents, deep research, and complex enterprise tasks.

  It is particularly strong at multi-step reasoning and planning, with high-throughput inference designed for high-volume agent pipelines. It is part of the NVIDIA Nemotron family of open models for agentic AI.

Ox Alpha:
* Ox Alpha is a reasoning model designed for coding, sustained agentic work, and production workloads. It is suited for long-horizon software engineering, complex reasoning, and workflows that combine text with visual context.

  Ox Alpha is a stealth model. It is developed and operated by a third-party provider who has chosen to remain anonymous during this preview.

(It was subsequently revealed to be GLM 5.3 Flash, which encouraged me to actually pay for that model later on.)

GLM 5.3 Flash:
* GLM-5.3-Flash is a native multimodal model from Z.ai. It is suited for efficient coding and long-horizon agent tasks. Its hybrid sparse and linear attention architecture maintains accurate long-context behavior while reducing compute overhead.

Poolside: Laguna S 2.1:
* Laguna S 2.1 is the latest coding agent model from Poolside. Laguna S 2.1 is a 118B total parameter model with 8B active parameters, scoring 70.2% on Terminal-Bench 2.1 and 40.4% on DeepSWE, making it one of the strongest coding models in its category. Open-weight under the OpenMDW-1.1 license.

Dots3-Note Preview:
* Dots3-Note Preview is an open-weight mixture-of-experts model from Dots Studio, with 16B active parameters out of 280B total. It is the lightest model in the Dots 3 family and is suited for reasoning, coding, multimodal understanding, long-context processing, and multi-step agent workflows.

Space Bunny Alpha:
* Space Bunny Alpha is an anonymous large model with blazing-fast inference, strong coding capabilities and native multimodal input support. It delivers adjustable reasoning effort, and a 1M-token context window.

  Space Bunny Alpha is a stealth model. It is developed and operated by a third-party provider who has chosen to remain anonymous during this preview. 

# Reflections on the development process

This is extremely subjective, of course. I was learning the tools and techniques as I went. I also started off with relatively basic free models which were probably competent enough to do a good job in parts but also misled me into thinking I could trust them with the modest architectural aspects of a small and fairly standard Android app like this. This write up is also taking place towards the end of an on-and-off three month development and I might have forgotten precisely how things went.

I think I started off on the right track, with SPEC.md and some associated documentation. I did start off with a DECISIONS.md file to record ongoing micro-decisions, but in practice this turned into a huge unreadable mess and towards the end of development I got an LLM to extract any truly important things from it, merge them into other documents and took it out of the project.

I should have, perhaps via some more specific coding standards and/or via more thorough code review, paid more attention to the basic architecture to start with. Hy3 was used with aider for almost all of the initial implementation. It felt like we were flying through the implementation and it was coming together nicely - almost done, in fact. I did lightly review the code for all commits, but I allowed myself to take things on trust to a certain extent, planning to do a more thorough review later on. When I did end up taking a closer look, I felt like the app crumbled in front of my eyes and my morale plummeted. I don't think it would be fair to blame Hy3 for this given the circumstances - it did feel quite powerful and my process might have played a significant part in the problem. It's really hard to say.

With a mixture of manual tweaks, iterated ChatGPT discussion and some directed work from a variety of models, I did manage to salvage the internal architecture and nudged it into something I felt reasonably comfortable with. I suspect that if I were to start the project from scratch with what I know now, and to use more sophisticated models like GLM 5.3 Flash, I'd be less likely to have got into such a mess, but I really don't know. It's possible that the more agentic style of opencode would also have helped get the app off to a better start, avoided the architectural mess and might have given Hy3 more of an opportunity to shine. aider does feel a little more controlled (which is particularly reassuring when you're actually paying for tokens) but in general the opencode style experience feels more comfortable. I haven't tried other harnesses yet, but intend to.

I did have the LLMs develop test cases throughout development, and these got more and more refined after the big manual tweak and morale dip phase. I do feel some of the more sophisticated models are almost too good at and too keen to write tests for every little aspect of the system and they can perhaps become too large a proportion of the code base. I do more-or-less understand all of the actual app code, but my personal code review of the tests has been much more perfunctory. Since they do seem to pass reliably and probably do add some value, I haven't felt the need to try to heavily prune them yet.

Overall I don't think the app code is bad or unmaintainable by a human. The comments do have a distinctive and slightly alien tone, a bit laden with random references to tests, specific failures that prompted changes and so on. Some of the comments are mine, sometimes tweaked or reviewed by LLMs. It's possible that being clearer about the writing style (and not having things like DECISIONS.md) in a future project would help in this area.

I honestly feel the code itself is not bad at all, but the mountain of documentation is just awful. It contains a fair amount of genuine insight, theoretically useful checklists and observations - so I feel I shouldn't delete it - but it's all mixed in with so much fluff, fake process and written in such a pseudo-corporate LLM jargon that it becomes pragmatically worthless except as fodder for future AI interactions. At several points, after extended discussion and code iterations, I asked LLMs to write up human-targeted "pseudo blog post" discussion of the issues we'd covered. While I don't claim these are accurate, free from excessive detail or written in natural language, they are probably some of the more useful LLM documentation outputs.

# In hindsight opinions on the models used

*This is not a formal review.* I used different models at different stages of the project with different tools and different amounts of experience with the LLM-assisted develoment workflow. I just want to note down my honest impressions having completed the first release.

Looking back at the development, GLM 5.3 Flash/Ox Alpha is definitely my favourite model. Space Bunny Alpha is very good and I'm glad it's been available free, but it feels — obviously this is very subjective, and may just be an artefact of getting access to it during the final polish and cleanup stages — a little over-eager and not quite as solid as GLM.

Hy3 is definitely a serious and powerful model. My experience of working with it was less impressive than with GLM 5.3 and Space Bunny Alpha, although it's difficult to separate the model from the way I used it: Hy3 did a lot of the heavy lifting early in the project, when I was less experienced with LLM-assisted development and was giving it less architectural direction and human code review. It's quite possible that I would have got much better results from it if I'd driven it better, or if I'd used a tool like opencode that might have compensated for some of the shortcomings in my workflow. It's no longer free, so I can't really say how it compares today, but I wouldn't want to dismiss it simply because I've been particularly impressed with GLM.

Dots3 also felt powerful, although my experience with it wasn't quite at the level of GLM or Space Bunny Alpha. I got some decent use out of it; I might have preferred GLM by that point, but Dots3 was free and I wanted to try it, and it did a solid job.

The various smaller models all worked adequately well for basic tasks. I generally used them because they were free rather than because I thought they were particularly impressive. If they were all I had, I suspect they could have done more than I asked for - but it was nice to have the bigger guns available.



